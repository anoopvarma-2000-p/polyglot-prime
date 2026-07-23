# Aetna S3 Channel — Database Flow Analysis

**Source file:** [`Aetna S3.xml`](Aetna%20S3.xml)
**Engine:** Mirth Connect / NextGen Connect ("BridgeLink") channel export, schema version `26.3.1`
**Channel name:** `Aetna S3`
**Database:** PostgreSQL, schema `mco_audit` (connection built per-script from `configurationMap` keys `jdbdcDriver` / `jbdcUrl` / `dbName` / `dbPassword` via `DatabaseConnectionFactory.createDatabaseConnection(...)`)

> This is the S3-based successor to the plain SFTP `Aetna.xml` channel (see sibling `Aetna-channel-analysis.md`). Source and several destinations read/write S3 objects (`txd-sbx-mco-sftp-inbound` bucket) instead of a mounted SFTP path, but the `mco_audit.*` database flow described below is functionally the same audit/tracking pipeline.

This document focuses **only** on the database access pattern: every table touched, every SELECT / INSERT / UPDATE statement in the channel, where it lives (source transformer vs. which destination), and the order/conditions under which it fires.

---

## 1. Tables touched

All tables live in the `mco_audit` schema. No `DELETE` statements exist anywhere in the channel — "deletion" is always logical (`is_active = '0'`).

| Table | Role | Written by |
|---|---|---|
| `mco_audit.mco` | Static MCO reference table (code → id/name lookup) | **Read-only** from this channel |
| `mco_audit.mco_records` | One row per **root file** (a logical monthly submission, keyed by `root_file_name`); the "batch" | Source transformer, `DB Calls`, `Stream file to SFTP`, `File rejection in DB` |
| `mco_audit.mco_record_details` | One row per **physical file attempt** (original + each resubmission) under a batch; the "batch details" | Source transformer, `DB Calls`, `Stream file to SFTP`, `File rejection in DB`, every destination's error handler |
| `mco_audit.mco_record_error_logs` | Append-only error log linked to a batch/batch-details row, holding a JSON payload and an `error_type_id` | `DB Calls`, `File rejection in DB`, every destination's error handler |

### 1.1 `mco_audit.mco` (reference)

| Column | Used as |
|---|---|
| `mco_id` | looked up by `mco_code`, cached in `globalChannelMap.mcoId` |
| `mco_name` | looked up alongside, cached in `globalChannelMap.mcoName` |
| `mco_code` | lookup key (`"AET"` for this channel) |
| `is_active` | filter (`= '1'`) |

Only query pattern used everywhere:
```sql
select mco_id, mco_name from mco_audit.mco where mco_code = ? and is_active='1'
```

### 1.2 `mco_audit.mco_records` (batch / root file)

Columns referenced across all INSERT/UPDATE statements:

`batch_id` (PK, serial, returned via `RETURNING`) · `root_file_name` · `created_on_utc` · `updated_on_utc` · `total_records` · `processed_count` · `errored_count` · `is_active` · `status` · `mco_id` (FK → `mco.mco_id`) · `file_size_in_kb` · `resubmitted_count` · `sent_to_datalake_time` · `qa_completed_time` · `sent_to_mco_time` · `reporting_month`

`status` lifecycle values seen: `INPROGRESS` → `FULLY_PROCESSED` | `PARTIALLY_PROCESSED` | `ERRORED` | `FILE_REJECTED` | `MIRTH_INTERNAL_ERROR`.

### 1.3 `mco_audit.mco_record_details` (per-attempt detail)

Same shape as `mco_records` but scoped to one physical file/attempt, plus `batch_id` as an FK back to the parent batch:

`batch_details_id` (PK, serial, returned) · `current_file_name` · `file_size_in_kb` · `total_records` · `created_on_utc` · `updated_on_utc` · `is_active` · `status` · `batch_id` (FK) · `reporting_month` · `mco_id` (FK) · `processed_count` · `errored_count` · `qa_completed_time` · `sent_to_datalake_time` · `sent_to_mco_time`

On a resubmission, the previous "active" details row is soft-deactivated (`is_active='0'`) and a fresh row is inserted rather than reused.

### 1.4 `mco_audit.mco_record_error_logs` (append-only)

`batch_id` (FK) · `error_json` (jsonb payload — validation errors, exception stack, or file-rejection reason) · `created_on_utc` · `updated_on_utc` · `batch_details_id` (FK) · `is_active` · `error_type_id` (FK into a code-configured `errorTypes` map: `FIELD_VALIDATION_ERROR`, `MIRTH_INTERNAL_ERROR`, `LAMBDA_INTERNAL_ERROR`, `FILE_REJECTION_ERROR`)

Insert shape used everywhere (only the bound params/`error_type_id` change):
```sql
insert into mco_audit.mco_record_error_logs
  (batch_id, error_json, created_on_utc, updated_on_utc, batch_details_id, is_active, error_type_id)
values (?::bigint, ?::json, current_timestamp, current_timestamp, ?::bigint, '1', ?)
```

---

## 2. Execution order and where DB access happens

Destinations run in this order (as laid out in the XML; filters on each destination decide whether it actually fires for a given message/batch):

```
Source (S3 File Reader, JS batch-splitter)
  └─ Source Transformer (JavaScript)                          ★ heavy DB read/write
        │
        ▼
  2  Original File Writer, Put data in global map             (DB only in generic error handler)
  4  Success Data          → writes valid rows to S3           (DB only in generic error handler)
  5  Error Data            → writes invalid rows to S3          (DB only in generic error handler)
  6  Stream file to SFTP   → pushes success file to NYeC        ★ status timestamp UPDATEs
  3  DB Calls              → finalizes counts/status            ★ core status UPDATE
  7  Send Rejection Receipt File → S3 rejection receipt         (DB only in generic error handler)
  8  Send Success Receipt  → S3 success receipt                 ★ SELECT to build receipt content
 10  File Rejection Alert  (email)                              (DB only in generic error handler)
 11  Success File Alert    (email)                               (DB only in generic error handler)
 12  File Error Alert      (email)                               (DB only in generic error handler)
 13  Archive Success Receipt → S3 archive copy                  (no DB at all)
 14  Archive Rejection Receipt → S3 archive copy                (no DB at all)
 16  File rejection in DB                                       ★ rejection-specific INSERT/UPDATE
  9  Clear Global Map                                           (no DB)
 15  Destination 1 (SFTP File Writer — DISABLED, legacy)         (no DB)
```

`★` = stages with meaningful business-logic DB access (detailed below). Every other destination only touches the database inside a generic `catch` block that logs a `MIRTH_INTERNAL_ERROR` (see §5).

---

## 3. Source Transformer — detailed DB flow

The source transformer runs once per batch chunk (`batchSequenceId`), but most DB work is gated to `currentBatch == 1` (the first chunk of a file) since batch/file identity only needs to be resolved once.

### Step 1 — MCO lookup (first batch only)
```sql
select mco_id, mco_name from mco_audit.mco where mco_code = ? and is_active='1'
```
Caches `mcoId` / `mcoName` into `globalChannelMap`.

### Step 2 — "same MCO, same month already has an in-progress batch?" guard
```sql
select batch_id, status, created_on_utc
from mco_audit.mco_records
where mco_id = ?::bigint and is_active='1'
order by batch_id desc limit 1
```
Used to detect a same-day duplicate submission (`EC082`) by comparing the derived folder name from `created_on_utc` against today's folder name.

### Step 3 — resubmission dedupe check (only if filename has `RS_`)
```sql
select batch_id from mco_audit.mco_record_details
where current_file_name = ?
  and status not in ('FILE_REJECTED','MIRTH_INTERNAL_ERROR','LAMBDA_INTERNAL_ERROR')
  and is_active='1'
```
If a batch is already found for this exact resubmission filename → reject as duplicate (`EC003`).

### Step 4 — root-file lookup (the central branch point)
```sql
select batch_id, resubmitted_count, status, total_records, reporting_month, errored_count
from mco_audit.mco_records
where root_file_name = ? and status not in ('FILE_REJECTED') and is_active='1'
```
Three outcomes:

**a) No row found (`batch_id == null`) → brand-new file**
- If it's a resubmission (`RS_` filename) with no matching original → reject (`EC006`).
- Else, create the batch:
  ```sql
  insert into mco_audit.mco_records
    (root_file_name, created_on_utc, updated_on_utc, total_records, processed_count,
     errored_count, is_active, status, mco_id, file_size_in_kb, resubmitted_count,
     sent_to_datalake_time, qa_completed_time, sent_to_mco_time, reporting_month)
  values (?, now(), now(), ?, null, null, '1', 'INPROGRESS', ?::bigint, ?, ?, null, null, null, ?::varchar)
  returning batch_id
  ```
  ```sql
  insert into mco_audit.mco_record_details
    (current_file_name, file_size_in_kb, total_records, created_on_utc, updated_on_utc,
     is_active, status, batch_id, reporting_month, mco_id)
  values (?, ?, ?, now(), now(), '1', 'INPROGRESS', ?, ?::varchar, ?::bigint)
  returning batch_details_id
  ```

**b) Row found, not a resubmission, but not `INPROGRESS`/`total_records=0`** → file already fully processed before → reject as duplicate (`EC003`).

**c) Row found, `status='INPROGRESS'` and `total_records=0`** → a prior attempt errored out technically; treat as a clean retry:
  ```sql
  select batch_details_id from mco_audit.mco_record_details
  where batch_id = ?::bigint and is_active='1'
    and status not in ('FILE_REJECTED','MIRTH_INTERNAL_ERROR','LAMBDA_INTERNAL_ERROR')
  ```

**d) Row found, this is a genuine resubmission** — validated against `FULLY_PROCESSED` (reject `EC086`), `INPROGRESS` (reject `EC006`), reporting-month mismatch (reject `EC087`); otherwise:
  ```sql
  update mco_audit.mco_records
  set updated_on_utc = now(), resubmitted_count = ?
  where batch_id = ?::bigint
  ```
  ```sql
  select batch_details_id from mco_audit.mco_record_details
  where batch_id = ?::bigint and is_active='1'
    and status not in ('FILE_REJECTED','MIRTH_INTERNAL_ERROR','LAMBDA_INTERNAL_ERROR')
  ```
  Deactivate the previous attempt row, then insert a fresh one:
  ```sql
  update mco_audit.mco_record_details set is_active='0' where batch_details_id = ?::bigint
  -- (or, if none found:) where batch_id = ?::bigint
  ```
  ```sql
  insert into mco_audit.mco_record_details
    (current_file_name, file_size_in_kb, total_records, created_on_utc, updated_on_utc,
     is_active, status, batch_id, reporting_month, mco_id)
  values (?, ?, ?, now(), now(), '1', 'INPROGRESS', ?::bigint, ?::varchar, ?::bigint)
  returning batch_details_id
  ```

`batch_id` / `batch_details_id` are then cached into `globalChannelMap` for every downstream destination to reuse. The DB connection opened for step 1–4 is explicitly closed (`dbConn.close()`) at the end of this block.

### Step 5 — "reprocess" re-hydration path
When `sourceMap.get("reprocessed") == true` and the batch is complete, the transformer re-opens a connection and re-derives `mco_id`/`mco_name` (same query as Step 1), then re-resolves `batch_id`/`batch_details_id`:
```sql
select batch_id, resubmitted_count, status, total_records
from mco_audit.mco_records where root_file_name = ? and is_active='1'
```
```sql
select batch_details_id from mco_audit.mco_record_details
where current_file_name = ?
  and status not in ('FILE_REJECTED','MIRTH_INTERNAL_ERROR','LAMBDA_INTERNAL_ERROR')
  and is_active='1'
```

### Step 6 — resubmission "record count didn't change" rollback
Once field-level validation runs and `batchComplete == true` on a resubmission, if the newly computed error count equals the previously recorded error count from Step 4 (i.e. nothing actually changed), the resubmission is rejected (`EC085`) and the earlier `resubmitted_count` bump is undone:
```sql
select resubmitted_count from mco_audit.mco_records where batch_id = ?::bigint and is_active='1'
```
```sql
update mco_audit.mco_records
set updated_on_utc = now(), resubmitted_count = ?   -- (previous value - 1)
where batch_id = ?::bigint
```

---

## 4. Destination-level DB flow

### 4.1 `Stream file to SFTP` (metaDataId 6)
After pushing the success/error file over SFTP/S3 (three branches: threshold-exceeded / partial-success / full-success), it stamps timestamps on both parent rows:
```sql
update mco_audit.mco_records
set sent_to_mco_time = now(), updated_on_utc = now()          -- threshold-exceeded branch
where batch_id = ?::bigint
```
```sql
update mco_audit.mco_records
set sent_to_datalake_time = now(), sent_to_mco_time = now(), updated_on_utc = now()  -- partial-success branch
where batch_id = ?::bigint
```
```sql
update mco_audit.mco_records
set sent_to_datalake_time = now(), updated_on_utc = now()      -- full-success branch
where batch_id = ?::bigint
```
Each variant has a matching `update mco_audit.mco_record_details ... where batch_details_id = ?::bigint` alongside it.

### 4.2 `DB Calls` (metaDataId 3) — the core status finalizer
Runs after all batches of the file have been streamed. Computes final `status` from an error-threshold check:
- `ERRORED` if `(totalRecords * thresholdValue) / 100 <= errorRecords`
- `FULLY_PROCESSED` if no errors at all
- otherwise `PARTIALLY_PROCESSED`

```sql
update mco_audit.mco_records
set status = ?, qa_completed_time = now(), processed_count = ?::bigint,
    errored_count = ?::bigint, total_records = ?::bigint, file_size_in_kb = ?,
    updated_on_utc = now()
where batch_id = ?::bigint
-- skipped when $('isResubmission') is true
```
```sql
update mco_audit.mco_record_details
set status = ?, qa_completed_time = now(), processed_count = ?::bigint,
    errored_count = ?::bigint, total_records = ?::bigint, file_size_in_kb = ?,
    updated_on_utc = now()
where batch_details_id = ?::bigint
```
If there were validation errors, logs them:
```sql
insert into mco_audit.mco_record_error_logs (..., error_type_id) values (..., FIELD_VALIDATION_ERROR)
```
Reads back the latest per-attempt error count:
```sql
select errored_count from mco_audit.mco_record_details
where batch_details_id = ?::bigint and status != 'FILE_REJECTED' and is_active='1'
```
Then reconciles the **batch-level** `processed_count`/`errored_count` using a correlated subquery so counts stay consistent across resubmissions of the same root file:
```sql
update mco_audit.mco_records
set processed_count = (
      (select total_records from mco_audit.mco_records where batch_id = ?::bigint and is_active='1')
    - (select errored_count from mco_audit.mco_record_details where batch_details_id = ?::bigint and is_active='1')
    ),
    errored_count = ?::bigint,
    qa_completed_time = now()
where batch_id = ?::bigint
-- (a second, near-identical variant additionally sets status=? and fires when NOT an
--  'ERRORED'+resubmission case)
```

### 4.3 `Send Success Receipt` (metaDataId 8)
Pure read, to build the human-readable receipt body sent back to Aetna:
```sql
select root_file_name, total_records, processed_count, errored_count, sent_to_datalake_time, status
from mco_audit.mco_records
where batch_id = ?::bigint and is_active='1'
```

### 4.4 `File rejection in DB` (metaDataId 16)
Fires whenever the source transformer marked the file as rejected (bad filename, bad header, duplicate, threshold, EOF-only, etc. — see `globalChannelMap.invalidFileReason`). Behavior branches on which error code triggered the rejection:

- **`EC084`** (Lambda-side rejection, file never even reached Mirth's row validation): looks up the batch, then inserts a fresh detail row directly as rejected:
  ```sql
  select batch_id, resubmitted_count, status, total_records
  from mco_audit.mco_records where root_file_name = ? and is_active='1'
  ```
  ```sql
  insert into mco_audit.mco_record_details
    (current_file_name, file_size_in_kb, total_records, created_on_utc, updated_on_utc,
     is_active, status, batch_id, reporting_month, mco_id)
  values (?, ?, ?, now(), now(), '1', 'LAMBDA_INTERNAL_ERROR', ?::bigint, null, ?::bigint)
  returning batch_details_id
  ```

- **`EC085`** (resubmission that changed nothing) or **`EC088`** (duplicate header found mid-file): finds the most recent detail row and flips it to rejected:
  ```sql
  select batch_details_id from mco_audit.mco_record_details
  where batch_id = ?::bigint and is_active='1' order by batch_details_id desc limit 1
  ```
  ```sql
  update mco_audit.mco_record_details set status='FILE_REJECTED' where batch_details_id = ?::bigint
  ```
  For `EC088` specifically, the **parent batch** is also flipped:
  ```sql
  update mco_audit.mco_records set status='FILE_REJECTED' where batch_id = ?::bigint
  ```

- **All other rejection codes** (filename convention, header mismatch, threshold, etc.): inserts a new rejected detail row directly:
  ```sql
  insert into mco_audit.mco_record_details
    (current_file_name, file_size_in_kb, total_records, created_on_utc, updated_on_utc,
     is_active, status, batch_id, reporting_month, mco_id)
  values (?, ?, ?, now(), now(), '1', 'FILE_REJECTED', ?::bigint, null, ?::bigint)
  returning batch_details_id
  ```

In every branch, an error log row is written with either `LAMBDA_INTERNAL_ERROR` (for `EC084`, using the Lambda stack trace) or `FILE_REJECTION_ERROR` (for everything else, using the rejection-reason JSON) as `error_type_id`.

---

## 5. Generic error-handling pattern (repeated in ~10 destinations)

Every destination — `Original File Writer`, `Success Data`, `Error Data`, `Stream file to SFTP`, `DB Calls`, `Send Rejection Receipt File`, `Send Success Receipt`, `File Rejection Alert`, `Success File Alert`, `File Error Alert` — wraps its main logic in a `try { ... } catch (err) { ... }` where the `catch` opens a DB connection (if one isn't already open) and records the failure the same way every time:

```sql
insert into mco_audit.mco_record_details
  (current_file_name, file_size_in_kb, total_records, created_on_utc, updated_on_utc,
   is_active, status, batch_id, reporting_month, mco_id)
values (?, 0, 0, now(), now(), '1', 'MIRTH_INTERNAL_ERROR', ?::bigint, null, ?::bigint)
returning batch_details_id
```
```sql
insert into mco_audit.mco_record_error_logs
  (batch_id, error_json, created_on_utc, updated_on_utc, batch_details_id, is_active, error_type_id)
values (?::bigint, ?::json, now(), now(), ?::bigint, '1', <MIRTH_INTERNAL_ERROR type id>)
```
then re-throws, clears `globalChannelMap`, and (if the batch wasn't complete) sets `skipAllDestinations = true` so remaining batches of the same file are skipped rather than silently continuing.

Destinations with **no** database access at all: `Archive Success Receipt`, `Archive Rejection Receipt`, `Clear Global Map`, and the disabled `Destination 1`.

---

## 6. `mco_records.status` state machine

```
                 ┌────────────┐
   new file  ──► │ INPROGRESS │
                 └─────┬──────┘
                       │ DB Calls destination (post-validation)
        ┌──────────────┼───────────────────┐
        ▼              ▼                   ▼
FULLY_PROCESSED  PARTIALLY_PROCESSED     ERRORED   (error-rate ≥ threshold)
        │              │                   │
        └──────────────┴─────────┬─────────┘
                                  │ resubmission (RS_ file, root file unchanged)
                                  ▼
                            INPROGRESS (new mco_record_details row,
                            old one deactivated; resubmitted_count++)

Any stage │ exception in a destination script ──► MIRTH_INTERNAL_ERROR
Any stage │ filename/header/duplicate/threshold rejection ──► FILE_REJECTED
Lambda-side pre-check failure ──► LAMBDA_INTERNAL_ERROR (mco_record_details only)
```

`is_active` on `mco_record_details` acts as a per-batch "current attempt" flag: only one row per `batch_id` should be `is_active='1'` at a time; every resubmission deactivates the prior attempt before inserting the new one.

---

## 7. Code templates bundled in the export but **not called** by this channel

The export also carries three server-wide Mirth code-template libraries (`MCO Code Template` with an `insertLog` function, `TechBD Functions` with `setErrorResponse`, and the stock `checkContactTypes` library with a `getCOVIDConn` helper). Grepping the channel's own scripts shows none of `insertLog(`, `setErrorResponse(`, or `getCOVIDConn(` are actually invoked from `Aetna S3` — they're attached because the libraries are marked "include on all channels" at the server level, not because this channel uses them. They are omitted from the flow above.

---

## 8. Quick reference — all distinct SQL statements

| # | Statement (shape) | Table | Op | Fired from |
|---|---|---|---|---|
| 1 | `select mco_id, mco_name ... where mco_code=? and is_active='1'` | `mco` | SELECT | Source transformer (Steps 1, 5) |
| 2 | `select batch_id, status, created_on_utc ... where mco_id=? ... order by batch_id desc limit 1` | `mco_records` | SELECT | Source transformer (Step 2) |
| 3 | `select batch_id from mco_record_details where current_file_name=? and status not in (...)` | `mco_record_details` | SELECT | Source transformer (Step 3) |
| 4 | `select batch_id, resubmitted_count, status, total_records, reporting_month, errored_count ... where root_file_name=?` | `mco_records` | SELECT | Source transformer (Step 4) |
| 5 | `insert into mco_records (...) ... returning batch_id` | `mco_records` | INSERT | Source transformer (Step 4a) |
| 6 | `insert into mco_record_details (...) ... returning batch_details_id` | `mco_record_details` | INSERT | Source transformer (Step 4a, 4d), reprocess/rejection paths |
| 7 | `select batch_details_id from mco_record_details where batch_id=? and is_active='1' and status not in (...)` | `mco_record_details` | SELECT | Source transformer (Step 4c, 4d) |
| 8 | `update mco_records set updated_on_utc=now(), resubmitted_count=? where batch_id=?` | `mco_records` | UPDATE | Source transformer (Step 4d, Step 6 rollback) |
| 9 | `update mco_record_details set is_active='0' where batch_details_id=?` (or `where batch_id=?`) | `mco_record_details` | UPDATE | Source transformer (Step 4d) |
| 10 | `select resubmitted_count from mco_records where batch_id=? and is_active='1'` | `mco_records` | SELECT | Source transformer (Step 6) |
| 11 | `update mco_records set sent_to_mco_time/sent_to_datalake_time=now(), updated_on_utc=now() where batch_id=?` (3 variants) | `mco_records` | UPDATE | `Stream file to SFTP` |
| 12 | `update mco_record_details set sent_to_mco_time/sent_to_datalake_time=now(), updated_on_utc=now() where batch_details_id=?` (3 variants) | `mco_record_details` | UPDATE | `Stream file to SFTP` |
| 13 | `update mco_records set status=?, qa_completed_time=now(), processed_count=?, errored_count=?, total_records=?, file_size_in_kb=?, updated_on_utc=now() where batch_id=?` | `mco_records` | UPDATE | `DB Calls` |
| 14 | `update mco_record_details set status=?, qa_completed_time=now(), processed_count=?, errored_count=?, total_records=?, file_size_in_kb=?, updated_on_utc=now() where batch_details_id=?` | `mco_record_details` | UPDATE | `DB Calls` |
| 15 | `select errored_count from mco_record_details where batch_details_id=? and status != 'FILE_REJECTED' and is_active='1'` | `mco_record_details` | SELECT | `DB Calls` |
| 16 | `update mco_records set processed_count=(subquery), errored_count=?, qa_completed_time=now() [+ status=?] where batch_id=?` (2 variants) | `mco_records` | UPDATE | `DB Calls` |
| 17 | `select root_file_name, total_records, processed_count, errored_count, sent_to_datalake_time, status from mco_records where batch_id=?` | `mco_records` | SELECT | `Send Success Receipt` |
| 18 | `select batch_id, resubmitted_count, status, total_records from mco_records where root_file_name=?` | `mco_records` | SELECT | `File rejection in DB` |
| 19 | `select batch_details_id from mco_record_details where batch_id=? and is_active='1' order by batch_details_id desc limit 1` | `mco_record_details` | SELECT | `File rejection in DB` (EC085/EC088) |
| 20 | `update mco_record_details set status='FILE_REJECTED' where batch_details_id=?` | `mco_record_details` | UPDATE | `File rejection in DB` (EC085/EC088) |
| 21 | `update mco_records set status='FILE_REJECTED' where batch_id=?` | `mco_records` | UPDATE | `File rejection in DB` (EC088) |
| 22 | `insert into mco_record_error_logs (...)` | `mco_record_error_logs` | INSERT | `DB Calls`, `File rejection in DB`, every destination's error handler, source transformer's error handler |

---

## 9. Notes / observations

- **No transactions / no rollback on partial failure.** Each `dbConn.executeUpdate`/`executeCachedQuery` call auto-commits independently; if a destination's `update mco_records` succeeds but the matching `update mco_record_details` throws, the two tables can end up out of sync until the generic error handler logs a `MIRTH_INTERNAL_ERROR` row (it does not attempt to undo the first update).
- **Soft-delete only.** `is_active` flags are the only "deletion" mechanism; historical rows for prior resubmission attempts are retained indefinitely in `mco_record_details`.
- **`batch_id`/`batch_details_id` are resolved once (first batch of a file) and threaded through `globalChannelMap`** for the rest of that file's chunks/destinations — later chunks never re-run the source transformer's lookup/insert logic (Steps 1–4 are gated on `currentBatch == 1`).
- **Every connection is manually opened and closed per script block** (`DatabaseConnectionFactory.createDatabaseConnection(...)` / `dbConn.close()`); there is no shared/pooled connection reused across destinations.
