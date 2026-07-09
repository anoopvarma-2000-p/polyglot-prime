# Aetna Channel — Analysis

**File:** [`Aetna.xml`](Aetna.xml)
**Engine:** Mirth Connect / NextGen Connect–compatible ("BridgeLink") channel export, schema version `26.3.1`
**Channel name:** `Aetna`
**Initial deploy state:** `STOPPED` (must be started manually after deploy)

> Note: the sibling file [`RouterChannel.xml`](RouterChannel.xml) in this same folder is an unrelated HTTP listener/CORS-redirection channel. It is not part of the Aetna file-processing pipeline described below.

## 1. What this channel does

`Aetna` is a per-MCO (Managed Care Organization) **SFTP file-intake pipeline** for New York State's ESMF program — an "Enhanced/Expanded Social/Member Data Extract Monthly File" style feed used to report Medicaid member social-risk and enrollment data (health-home enrollment, high-utilizer flags, SDOH/HRSN screening fields, waiver enrollment, chronic conditions, etc.) so it can be forwarded into NYeC's (New York eHealth Collaborative) data lake on behalf of **GRRHIO** (Greater Rochester Regional Health Information Organization).

Concretely, the channel:

1. Polls an SFTP-mounted inbound folder for files dropped by Aetna (`mco_code = "AET"`).
2. Validates the file name convention, header, and every pipe-delimited record against ~50 field-level business rules.
3. Splits valid vs. invalid records, writes "Success" and "Error" data files.
4. Streams the success file onward to NYeC's SFTP data lake (`nyec-transfer.nyehealth.org`) and archives copies internally.
5. Sends back machine-readable receipt files (success/rejection) to Aetna's outbound SFTP folder, plus human-readable email alerts.
6. Tracks every file/batch/record-error in a Postgres audit schema (`mco_audit.*`) for a monitoring dashboard.
7. Supports resubmissions (`RS_` prefixed files) and has a self-healing "reprocess" hook that can re-trigger itself via HTTP callback after certain transient errors.

This is one of several near-identical per-MCO channels (one per managed care organization); `AET`, the SFTP folder `mco-aetna`, and the root folder `/lza-prod-esmf` are the only Aetna-specific constants — everything else is templated off `globalChannelMap` values set at the top of the source transformer.

## 2. High-level flow

```
                         ┌───────────────────────────────────────────────────┐
                         │  SFTP inbound: /lza-prod-esmf/.../mirth_inbound   │
                         │  File Reader source, polls every 60s              │
                         └───────────────────────────┬───────────────────────┘
                                                     │  batched by line-count (chunkSize)
                                                     ▼
                    ┌──────────────────────────────────────────────────────────┐
                    │ Source Transformer (JavaScript)                          │
                    │  • filename convention + resubmission detection          │
                    │  • duplicate/already-processed checks against Postgres   │
                    │  • per-record validation (~50 rules, EC0xx error codes)  │
                    │  • builds validData[] / errorMessages[] / status         │
                    │  • dynamically prunes destinationSet based on outcome    │
                    └───────────────────────────┬──────────────────────────────┘
                                                ▼
        ┌─────────────────────────────────────────────────────────────────────────────┐
        │ Destinations (execution order, all "wait for previous"):                    │
        │                                                                             │
        │  2  Original File Writer / global-map accumulator (aggregates per-batch     │
        │     counters into globalChannelMap: recordsCount, totalErrors, errorJson…)  │
        │  4  Success Data      → writes valid rows to mirth_processed/Success_<file> │
        │  5  Error Data        → writes invalid rows + EOF marker to Error_<file>    │
        │  6  Stream file to SFTP → JSch SFTP push of Success file to NYeC data lake, │
        │       plus internal archive copies; branches on error-threshold %           │
        │  3  DB Calls          → finalizes mco_records/mco_record_details status     │
        │       (FULLY_PROCESSED / PARTIALLY_PROCESSED / ERRORED)                     │
        │  7  Send Rejection Receipt File → FileRejected_<file> to Aetna outbound     │
        │  8  Send Success Receipt        → Success_<file> receipt to Aetna outbound  │
        │ 10  File Rejection Alert  (email)                                           │
        │ 11  Success File Alert   (email)                                            │
        │ 12  File Error Alert     (email, partial success)                           │
        │ 13  Archive Success Receipt → archive/mco-aetna/prod/mco_success            │
        │ 14  Archive Rejection Receipt → archive/mco-aetna/prod/mco_error            │
        │ 16  File rejection in DB → updates mco_records/mco_record_details status    │
        │  9  Clear Global Map  → resets globalChannelMap once batchComplete          │
        │ 15  Destination 1 (SFTP File Writer, DISABLED) → legacy/backup direct       │
        │       SFTP push to nyec-transfer.nyehealth.org, superseded by dest 6        │
        └─────────────────────────────────────────────────────────────────────────────┘
```

Each destination has a **rule-builder filter** (e.g. `fileRejected != true`, `batchComplete == true`, `totalErrors == 0`) so only the relevant subset actually fires for a given outcome, and the source transformer additionally calls `destinationSet.remove(id)` to hard-skip destinations for whole classes of outcome (already-exists, invalid file, reprocess-in-progress, etc.).

## 3. Source connector

* **Transport:** File Reader over SFTP (`scheme=FILE`, `secure=true`, `passive=true`), polling `/lza-prod-esmf/internal/mco-aetna/prod/mirth_inbound` every 60,000 ms, `afterProcessingAction=DELETE`.
* **Batching:** a custom JavaScript batch splitter (`splitType=JavaScript`) reads the file line-by-line and groups lines into chunks of `configurationMap.get("chunkSize")` lines, so a single large file is processed as multiple sequential "batches" (`batchSequenceId`) inside one message, with `batchComplete` signaling the last chunk.
* **Global bootstrapping:** on every batch, `setMcoInfoInGlobalMap()` seeds `globalChannelMap` with `mco_code=AET`, `root_sftp_folder=/lza-prod-esmf`, `mco_sftp_folder=mco-aetna`.

### 3.1 File-level validation (first batch only)

* **Filename convention:**
  * Normal file: `AET_MDESMF_<14-digit timestamp>.txt`
  * Resubmission file: `RS_AET_MDESMF_<14-digit>_<14-digit>.txt`
  * Anything else → rejected with `EC001` (bad format) and/or `EC002` (naming convention).
* **Lambda-rejected passthrough:** if the filename contains `FileRejected_`, the file is treated as an upstream (Lambda) rejection — the reason/trace is extracted from the file content itself rather than re-validated.
* **Duplicate / already-processed detection** (via Postgres `mco_audit.mco_records` / `mco_record_details`):
  * Same root file already exists and isn't `FILE_REJECTED` → `EC003`/`EC006` depending on resubmission state.
  * Resubmission of a file that's already `FULLY_PROCESSED` → `EC086`; resubmission still `INPROGRESS` → `EC006`.
  * Resubmission whose reporting month differs from the computed current reporting month → `EC087`.
  * File containing only a header + `EOF` (no data rows) → `EC081`.
  * Multiple header rows detected mid-file → `EC088` (also deletes any partially-written Success/Error files for that name).
  * Mismatch between error count recomputed at `batchComplete` vs. what the DB expects for a resubmission → `EC085`.
* **Reporting month:** computed from `configurationMap` `startDate`/`endDate` cutoff-day window (a rolling "reporting month" concept — e.g. if today's day-of-month is past the cutoff, the reporting month rolls to next calendar month).
* On the very first batch, an `mco_audit.mco` lookup resolves `mco_id`/`mco_name` for `mco_code = 'AET'`, and new `mco_records` / `mco_record_details` rows are inserted with `status = INPROGRESS` (or updated for resubmissions, deactivating the prior `mco_record_details` row and inserting a fresh one, bumping `resubmitted_count`).

### 3.2 Per-record validation

Each line is split on `|` into a fixed 39-column schema (`MBR_ID`, `MBR_LASTNAME`, …, `EPOP_IDD`) and validated field-by-field. Rules generally follow this shape per field: **required → format/type → length/range → domain-specific business rule**, each pushing a distinct `EC0xx` error code (roughly `EC007`–`EC112`) tied to a row number. Representative rules:

| Field | Rules |
|---|---|
| `MBR_ID` (CIN) | required, alphanumeric, exactly 8 chars |
| `MBR_LASTNAME` / `MBR_FIRSTNAME` | required, ≤ 50 chars |
| `MBR_DOB` | required, `YYYYMMDD` valid calendar date, not in the future |
| `MBR_ADDR_LINE_1` / `MBR_CITY` | required, ≤ 50 chars, not literal `"NULL"` |
| `MBR_POSTALCODE` | required, numeric, exactly 5 digits |
| `MBR_COUNTY` | required, numeric, 2 or 5 digits, must match a configured county-code list |
| `PLAN_ID` | required, numeric, exactly 5 digits, must not be a placeholder (`00000`/`99999`) |
| `MBR_LOB` / `MBR_LOB_DESC` | required; description must be one of `Mainstream`/`MLTCP`/`MAP` |
| `MBR_CONT_PLAN_ENROLL_DT` | required, valid date, must fall within the current reporting month |
| `MBR_PROS_DISENROLL_DT` | required, valid date, must not be earlier than the reporting month start |
| ~20 `EPOP_*` / SDOH / waiver boolean flags (`EPOP_HIGHUTILIZER`, `EPOP_HHENROLLED`, `CHRONIC_CONDITIONS`, `UNCONTROLLED_ASTHMA`, `NUTRITION_THERAPY`, `HEATILLNESS_ERUC`, `COLDILLNESS_ERUC`, `THERMOREG_RISK`, `HEALTHY_HOMES`, `NUTRITION_COUNSEL`, `MTM_ILOS`, `OPWDD_WAIVER`, `TBI_WAIVER`, `NHTD_WAIVER`, `CHILDRENS_WAIVER`, `EPOP_IDD`, …) | required, must be one of `Y`/`N`/`U` |
| `MBR_TELE_NUM_HOME/CELL/OTHER`, `PCP_NPI`, `PCP_TELE_NUM` | optional, but if present must be exactly 10 numeric digits (or literal `NULL`) |
| `PCP_NPI_NAME` | ≤ 50 chars (or `NULL`) |

Two fields (`EPop_Under6`, `EPop_Youth_JJ_FC`) have their validation **commented out** — removed per an OHIP (NYS Office of Health Insurance Programs) requirement change, but left in the code for history.

Any row with ≥1 error is written to `errorMessages[]` (original line + row number + `;`-joined error codes/descriptions appended); error codes are also aggregated into an `errors` map (`{code: {rows:[...], description}}`) for the audit DB. Rows with zero errors get the resolved `mco_code` appended and go to `validData[]`.

### 3.3 Batch/file status

* Per-batch counters (`no_of_records`, `errorCount`, `processedCount`) are computed in the source transformer, then destination `2` accumulates them across batches into `globalChannelMap` (`recordsCount`, `totalErrors`, `totalProcess`, `totalFileSize`, `errorJson`).
* Final status (computed in destination `3`, "DB Calls", once `batchComplete`):
  * `ERRORED` — error rate ≥ `configurationMap.thresholdValue` % (see note below — effectively unreachable since threshold is configured at 100).
  * `FULLY_PROCESSED` — zero errors.
  * `PARTIALLY_PROCESSED` — some errors, below threshold.
* `channelMap.fileRejected` is the master switch used by nearly every destination's filter to distinguish "processed" from "file-level reject" outcomes.

## 4. Destinations in detail (execution order)

| # | Name | Type | Trigger condition | Purpose |
|---|---|---|---|---|
| 2 | Original File Writer, Put data in global map | JS Writer | always (unless removed) | Rolls per-batch counters/errors into `globalChannelMap`; on internal failure logs a `MIRTH_INTERNAL_ERROR` row to `mco_record_error_logs` |
| 4 | Success Data | File Writer | not rejected, processed > 0 | Appends valid rows (with header on first write) to `Success_<originalFilename>` under `mirth_processed/` |
| 5 | Error Data | File Writer | errors > 0, not rejected, batch complete | Appends invalid rows (with header + trailing `EOF`) to `Error_<originalFilename>` |
| 6 | Stream file to SFTP | JS Writer | batch complete, not rejected | Computes error-threshold %, then via `JSch` SFTP (key-based auth) pushes the Success file to NYeC's data lake (`/inbound/esmf/<Month-Year>/`) and copies error/success files into internal archive folders; updates `sent_to_datalake_time`/`sent_to_mco_time` on `mco_records`/`mco_record_details` |
| 3 | DB Calls | JS Writer | batch complete, not rejected | Computes final file status (`FULLY_PROCESSED`/`PARTIALLY_PROCESSED`/`ERRORED`), updates `mco_records`/`mco_record_details` counts, inserts `mco_record_error_logs` if there were field errors |
| 7 | Send Rejection Receipt File | File Writer | `fileRejected == true`, not a Lambda rejection | Writes a `FILENAME\|STATUS\|PROCESSED_DT\|ERROR_CODE\|ERROR_DESC` receipt as `FileRejected_<file>` to Aetna's outbound folder; response transformer calls the shared `insertErrorLog()` code-template function if the SFTP write itself errors |
| 8 | Send Success Receipt | File Writer | batch complete, zero errors, not rejected, file confirmed sent to data lake | Writes a `FILENAME\|TOTAL_RECORDS\|TOTAL_PROCESSED\|STATUS\|PROCESSED_DT\|STATUS_DESC` receipt to Aetna's outbound success folder |
| 10 | File Rejection Alert | JS Writer (email) | `fileRejected == true`, not Lambda rejection, destination 7 not errored | SMTP email "GRRHIO Alert: … File Rejected" to configured internal/external recipient list per MCO |
| 11 | Success File Alert | JS Writer (email) | batch complete, zero errors, not rejected, sent to DL | SMTP email "GRRHIO Alert: Monthly ESMF File Successfully Processed" |
| 12 | File Error Alert | JS Writer (email) | errors > 0, batch complete, not rejected | SMTP email "GRRHIO Alert: Error found in Monthly ESMF File" (partial-success notice) |
| 13 | Archive Success Receipt | File Writer | batch complete, zero errors, not rejected, sent to DL | Copies the success receipt into `archive/mco-aetna/prod/mco_success` |
| 14 | Archive Rejection Receipt | File Writer | `fileRejected == true`, not Lambda rejection, dest 7 not errored | Copies the rejection receipt into `archive/mco-aetna/prod/mco_error` |
| 16 | File rejection in DB | JS Writer | `fileRejected == true` | Reconciles `mco_records`/`mco_record_details` status to `FILE_REJECTED` for the various rejection error codes (`EC084` Lambda-internal, `EC085` count-mismatch, `EC088` duplicate header) |
| 9 | Clear Global Map | JS Writer | batch complete | Clears `globalChannelMap` so the next inbound file starts from a clean state |
| 15 | Destination 1 | File Writer (SFTP, **disabled**) | n/a | Legacy direct SFTP push (key auth, `rochester-rhio-key.ppk`) straight to `nyec-transfer.nyehealth.org`; superseded by the richer logic in destination 6 and left disabled |

## 5. Error handling & resiliency

* **Internal error trapping:** almost every destination wraps its logic in try/catch; on failure it opens a DB connection (if not already open), inserts a `MIRTH_INTERNAL_ERROR` row into `mco_record_details`, logs the raw JS error into `mco_record_error_logs` (`error_type_id` = `MIRTH_INTERNAL_ERROR`), then re-throws. The `finally` block clears `globalChannelMap` and, if the batch isn't complete yet, sets `skipAllDestinations` so subsequent batches of the same file don't keep re-processing after a failure.
* **Reprocessable errors:** the source transformer's top-level `catch` checks whether the thrown error's message is in a configured `reprocessableErrors` list. If so, it sets `isReprocessable`/`skipAllDestinations` and — once `batchComplete` — fires an asynchronous (3s-delayed) HTTP POST to `configurationMap.get("reprocessUrl")` with `{fileName, mcoCode}`, presumably to re-drop/re-trigger the file via an external Lambda/queue, then re-throws to fail the message cleanly.
* **Destination pruning:** the source transformer explicitly calls `destinationSet.remove(id)` in five distinct branches (skip-all, invalid-file-first-batch, invalid-file-later-batch, already-exists-first-batch, already-exists-last-batch) to hard-disable irrelevant destinations for the current message, on top of each destination's own filter rules — effectively a belt-and-suspenders design.
* **Threshold logic is effectively dormant:** both destination 6 and destination 3 compute `thresholdExceeded = (totalRecords * thresholdValue / 100) <= errorRecords`, but a comment in destination 6 notes *"This case will never hit since threshold is 100 now"* — i.e. the configured threshold is 100%, so the `ERRORED` (fail-whole-file) branch is currently unreachable in practice; files always land in `FULLY_PROCESSED` or `PARTIALLY_PROCESSED`.

## 6. External dependencies

**Postgres schema `mco_audit`:**
* `mco` — MCO code/name/id lookup (`mco_code = 'AET'`)
* `mco_records` — one row per physical file (root_file_name, status, counts, timestamps, resubmitted_count, reporting_month)
* `mco_record_details` — one row per file version/attempt (current_file_name, status, counts) — deactivated (`is_active='0'`) and re-inserted on resubmission
* `mco_record_error_logs` — JSON blob of validation or internal errors per batch, tagged with `error_type_id` (`FIELD_VALIDATION_ERROR` / `MIRTH_INTERNAL_ERROR` / `LAMBDA_INTERNAL_ERROR`)

**SFTP endpoints:**
* Inbound: `/lza-prod-esmf/internal/mco-aetna/prod/mirth_inbound`
* Internal processed/archive: `/mco-internal/internal/mco-aetna/prod/mirth_processed`, `/lza-prod-esmf/archive/mco-aetna/prod/{mco_success,mco_error}`
* Outbound receipts to Aetna: `/lza-prod-esmf/mco-aetna/esmf/outbound/{success,error}`
* Downstream data lake: `nyec-transfer.nyehealth.org` (`/inbound/esmf/<Month-Year>/`), key-based auth via `rochester-rhio-key` identity

**SMTP:** configured host/port/credentials in `configurationMap`, sending to per-MCO `to`/`cc` lists defined in a JSON `emailList` config value, with an internal/external switch (`isTesting`) and an `excludeEmailForRejection` suppression list.

**`configurationMap` keys referenced:** `jdbdcDriver`, `jbdcUrl`, `dbName`, `dbPassword`, `chunkSize`, `errorCodes`, `errorTypes`, `reprocessableErrors`, `reprocessUrl`, `countyCode`, `startDate`, `endDate`, `allowMultipleFiles`, `thresholdValue`, `sftpUserName`/`sftpPassword`/`sftpIp`/`sftpPort`, `destinationSftpUserName`/`Password`/`Ip`/`Port`, `smtpHostName`/`Port`/`Username`/`Password`/`FromEmail`, `emailList`, `isTesting`, `alertEmails`. None of these are defined inside this channel XML — they live in the Mirth server-wide Configuration Map (not exported here).

**Shared code template:** `insertErrorLog()` (in the bundled `codeTemplateLibraries` section of this export) is invoked from destination 7's response transformer to log SFTP-write failures. The export also carries unrelated shared libraries (`checkContactTypes`, `getCOVIDConn`, etc.) that are not used by this channel — they're artifacts of a full-server export bundled into the same file.

## 7. Notable observations

* This channel is **data-driven from constants at the very top of the source script** (`mco_code`, `root_sftp_folder`, `mco_sftp_folder`) — the rest of the logic is generic, suggesting Aetna's channel is one of several near-duplicate per-MCO clones sharing the same script body.
* The disabled destination 15 (direct password-less key SFTP to NYeC) looks like an earlier, simpler implementation that was replaced by the JS-scripted destination 6, which adds threshold branching, archiving, and DB timestamping — kept around disabled rather than deleted.
* Validation rules for two fields are commented out rather than removed, and two DOB/date-validation helper functions (`futureDOB`, `isFuturePlanDate`, `isPastEnrollDate`) reference `errorCodes["EC0100"]` in their catch blocks but that assignment is dead code (the variable is built but never used/pushed), so calendar-computation exceptions in those helpers currently fail *silently* (function returns `false`, causing a validation error under the *other* branch, but the intended EC0100-specific message is never surfaced).
* `messageStorageMode` is `DEVELOPMENT` and `initialState` is `STOPPED`, consistent with this being a QA/staging export rather than a live production deploy snapshot.
