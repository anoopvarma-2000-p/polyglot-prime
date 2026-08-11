# ESMF File Ingestion: Business Data Flow - Large data (Using Amida Care channel - AMI)

Source: `Amida Care.xml` (Mirth Connect channel "Amida Care", MCO short code **AMI**) and its deployed configuration values (`configuration-map/configuration_map_export.properties`).
This document explains **what happens to a submitted file and its records, in business terms**, from the moment Amida Care drops a monthly ESMF file until it lands with the state datalake and Amida Care gets a confirmation back. Technical detail (SQL, code, server names) is kept to the minimum needed to make the business rule unambiguous.

**What's new**: a dedicated explanation of how the pipeline processes a file in batches rather than all at once, and a worked example scaled up to a **100,000-record file** — including a description of the error-rate threshold rule (see §5 and §10).

---

## 1. Who's involved

| Party | Role |
|---|---|
| **Amida Care** | The Managed Care Organization (MCO). Drops a monthly member-roster file (and, when needed, corrected re-submissions) into their SFTP/S3 inbound folder. |
| **The pipeline (this Mirth channel)** | Validates the file and every member record in it, splits good records from bad ones, files paperwork (receipts) back to Amida Care, and forwards the clean data onward. |
| **State datalake (NYeC / HealtheConnections)** | The downstream system of record that receives the validated, "golden" file for the reporting month. |
| **Tracking database** (`mco_data.mco_records`, `mco_data.mco_record_details`, `mco_data.mco_record_error_logs`) | The system's memory of every file ever submitted, every attempt/resubmission of that file, and every validation error raised — used for the monitoring dashboard and for deciding how to treat the next file that comes in. |

---

## 2. The file Amida Care sends

- **Naming convention** (this is enforced — get it wrong and the file bounces before a single record is read):
  - First submission: `AMI_MDESMF_<14-digit timestamp>.txt`
  - Re-submission (a corrected re-send of a prior file): `RS_AMI_MDESMF_<original 14-digit timestamp>_<new 14-digit timestamp>.txt`
- **Format**: pipe (`|`) delimited text, one header row followed by one row per member, ending in a literal `EOF` line.
- **Header**: a fixed 40-column layout (member ID, name, DOB, address, plan/LOB, enrollment dates, a long list of "EPOP"/program-eligibility flags, waiver flags, phone numbers, PCP info, etc.). The header must match **exactly** (case-insensitive) or the whole file is rejected.
- **Size**: files can range from a handful of rows to well over 100,000 member rows. The pipeline never loads a file entirely into memory — see §4 for how it processes large files in slices.

---

## 3. High-level flow

```mermaid
flowchart TD
    A[Amida Care drops file on S3] --> B{Filename & header valid?}
    B -- No --> R[File rejected — no records processed]
    B -- Yes --> C{New file, resubmission,\nor duplicate?}
    C -- Duplicate / conflicting resubmission --> R
    C -- New or valid resubmission --> D[Validate every member record,\nprocessed in batches — see §4]
    D --> E[Split: valid records vs. error records]
    E --> F[Write Success file + Error file to staging]
    F --> G{What's the error rate\nfor the whole file?}
    G -- 0% --> H[Send full file to state datalake]
    G -- "above 0%, below threshold" --> I[Send only the good records to state datalake]
    G -- "at/above threshold" --> J[Nothing sent to datalake —\neven the good records are withheld]
    H --> K[Success receipt + confirmation email to Amida Care]
    I --> L[Error receipt + "please correct & resubmit" email]
    J --> L
    R --> M[Rejection receipt + rejection email to Amida Care]
    K --> N[(Tracking DB: FULLY_PROCESSED)]
    L --> O[(Tracking DB: PARTIALLY_PROCESSED or ERRORED)]
    M --> P[(Tracking DB: FILE_REJECTED)]
```

The pipeline processes the file in batches of rows rather than all at once (§4), but from a business standpoint the whole file is treated as one submission with one final outcome, decided only once the last batch has been read.

---

## 4. How large files are processed in batches

This is the mechanic that makes it possible for the pipeline to handle a 100,000-row file (or larger) without ever holding the whole file in memory, and it directly shapes what you'll see happening — and *not* see happening — while a big file is mid-flight.

### The slicing rule

The pipeline reads the file **500 lines at a time** (this is a configured value, `chunkSize = 500`, not a hard limit in the code — see §10). Each 500-line slice is processed as one self-contained "batch" that runs through the entire pipeline (validation → staging → conditionally the final wrap-up steps) before the next batch is read.

- A **100,000-record file** (100,000 data rows + 1 header row + 1 terminating `EOF` line = 100,002 lines) is therefore split into **201 batches**:
  - **Batch 1**: the header line + the first 499 data rows
  - **Batches 2–200** (199 batches): 500 data rows each (99,500 rows)
  - **Batch 201**: the final data row + the `EOF` line

### What each batch is responsible for

| Batch | What happens |
|---|---|
| **Batch 1** | Everything that only needs to happen once per file: look up the MCO's tenant record, validate the filename and header, check for duplicates/resubmission conflicts against the tracking DB, and — if all of that passes — create the initial tracking rows (`mco_records` + `mco_record_details`, both `status='INPROGRESS'`). Then it validates its own 499 data rows exactly like every other batch. |
| **Batches 2–200 (middle batches)** | Record-level validation only, for that batch's 500 rows. No file-level setup, no DB reads/writes for setup — that already happened in batch 1. Each batch's results (how many passed, how many failed, and the specific error codes) are added on top of a running total carried forward from the previous batch. |
| **Batch 201 (final batch)** | Validates the last data row, then encounters the `EOF` marker — this is the signal that tells the pipeline "no more data is coming." Hitting `EOF` triggers every one of the wrap-up steps described in Stages 4–6: compute the final outcome for the *entire file* (using the fully accumulated totals from all 201 batches), write the one-time final update to the tracking DB, route the success/error content to their destinations, and send the appropriate receipt(s)/email(s) to Amida Care. |

### The important consequence: the tracking DB updates twice, not 201 times

For a given attempt (an original file or a resubmission), the tracking DB (`mco_records` / `mco_record_details`) is written to **exactly twice**, regardless of whether the file has 10 rows or 100,000 rows:

1. **Once at the very start** (batch 1) — an `INSERT` creating the attempt with `status='INPROGRESS'` and counts at zero.
2. **Once at the very end** (the final batch) — an `UPDATE` with the fully accumulated final counts and final status (`FULLY_PROCESSED` / `PARTIALLY_PROCESSED` / `ERRORED` / `FILE_REJECTED`).

There is **no per-batch progress update** written to the database. While a 100,000-row file is working its way through its 201 batches, the tracking DB (and therefore the monitoring dashboard built on it) will keep showing `status='INPROGRESS'` with zero counts the entire time — it has no visibility into "batch 87 of 201 done." The only place that shows batch-by-batch progress while a large file is still in flight is the Mirth engine's own message log for the channel, which is an operational/technical view, not a business-facing one.

Practically, this means: **for a very large file, "how far along is it?" cannot be answered from the tracking DB until the file finishes entirely** — it is either still `INPROGRESS` (with no partial number to report) or it is done (with a final number).

### Row numbers stay correct across batches

Even though the file is being read 500 lines at a time, every validation error is still recorded against the row's **true position in the original file** (e.g., "row 54,213 — invalid postal code"), not its position within its own batch. The pipeline does this by tracking which batch number it's on and offsetting each row's in-batch position accordingly (adjusting for the header line, which only exists in batch 1). From Amida Care's perspective this is invisible — the error file they get back always references the real row number they'd find by opening their original file.

---

## 5. Stage-by-stage

### Stage 1 — File-level gatekeeping (before any member data is looked at)

The very first thing checked is whether the **file itself** is acceptable. Any failure here rejects the entire file — no member records are validated, no data goes anywhere except a rejection notice back to Amida Care. This check always happens on batch 1, before any per-record validation begins.

A file is rejected at this stage if:

| Condition | What it means in practice |
|---|---|
| Filename isn't a `.txt` file, or doesn't follow the `AMI_MDESMF_<timestamp>` / `RS_AMI_MDESMF_<ts>_<ts>` pattern | Wrong file type or someone renamed/mis-formatted the file |
| Header row doesn't exactly match the expected 40 columns | Wrong template, extra/missing/reordered columns |
| The file is empty (header immediately followed by `EOF`, no member rows) | Nothing to process |
| A second header row appears in the middle of the file | File got corrupted/concatenated incorrectly |
| The file is an exact duplicate — same file already fully or partially processed this cycle | Prevents double-processing the same submission |
| More than one file submitted for the same reporting month, when the MCO isn't allowed multiple monthly files | Enforces "one file per month" unless explicitly configured otherwise |
| It's flagged upstream (by the SFTP/Lambda layer) as a malformed transfer before it even reaches this pipeline | Transport-level problem, not a data problem |

**Outcome of a file-level rejection**: a rejection receipt file is written back to Amida Care's own outbound folder (`.../esmf/outbound/error/FileRejected_<filename>`), archived internally, an email alert goes out to Amida Care, and the attempt is logged in the tracking DB with status **`FILE_REJECTED`**. No member records are validated or forwarded anywhere — and since this all happens on batch 1, none of the remaining 200 batches of a large file are even processed.

### Stage 2 — Is this a new file or a re-submission? (see §7 for the full re-submission flow)

If the filename doesn't start with `RS_`, it's treated as a brand-new monthly file: a new tracking record is created with status **`INPROGRESS`** on batch 1.

If it does start with `RS_`, the pipeline looks up the *original* file it's correcting and applies the re-submission rules described in §7 — again, entirely on batch 1.

### Stage 3 — Record-by-record validation

Once the file itself is accepted, **every single member row, in every batch,** is validated independently against business rules, for example:
- Required fields present (Member ID, last name, first name, DOB, address, plan, LOB, etc.)
- Member ID (CIN) is exactly 8 alphanumeric characters
- Dates are valid calendar dates and not in the future / outside the reporting window (enrollment date not after month-end, disenrollment date not before month-start)
- Postal code is 5 digits; county code matches the state's approved county list
- Plan/Line-of-Business is one of the allowed values (`Mainstream`, `MLTCP`, `MAP`)
- All of the "EPOP" program flags (high utilizer, pregnancy/postpartum, under-18, chronic conditions, waivers, etc.) are one of `Y` / `N` / `U`
- Phone numbers and NPI numbers, where provided, are the correct length and numeric

Each rule maps to a specific error code (e.g., "Member ID missing," "Invalid date format," "County code not recognized"). A record can fail multiple rules at once — every failure is recorded against that row, not just the first one found.

**A record either passes every rule or it doesn't** — there is no partial-record outcome. It becomes either a **valid record** or an **error record**.

### Stage 4 — Splitting the file: valid vs. error records

- All **valid records**, across all batches, are collected into a running "Success" data set.
- All **error records**, across all batches, are collected into a running "Error" data set, each one tagged with its true row number, the error code(s), and a plain-language description of what was wrong.

Both sets are staged (in AWS S3) and grow batch by batch as the file is processed, so that even a very large file can be handled in manageable 500-row slices without losing track of what's succeeded or failed so far.

### Stage 5 — Deciding the outcome and where data goes

Once the **final batch** has been read (`EOF` reached), the pipeline looks at the fully accumulated totals for the whole file and decides one of three outcomes, based on what fraction of records failed:

| Outcome | When it happens | What's sent to the state datalake | Tracking DB status |
|---|---|---|---|
| **Fully successful** | Every record passed validation, zero errors | The complete file | `FULLY_PROCESSED` |
| **Partially successful** | Some records failed, but the error rate is **below** the configured threshold (10% in this environment) | Only the valid records (bad records are held back) | `PARTIALLY_PROCESSED` |
| **Errored — threshold exceeded** | The error rate is **at or above** the configured threshold (10%) | **Nothing** — even the records that individually passed validation are withheld and not forwarded | `ERRORED` |

The actual deployed configuration (`thresholdValue = 10`) means this rule **is active**: any file where 10% or more of its records fail validation gets **none** of its data forwarded to the datalake — not even the valid 90%-or-fewer records — until Amida Care resubmits a corrected file that brings the error rate below 10%. See the 100,000-record worked example in §6 for exactly what this looks like at scale, and §10 for the corrected configuration reference.

In every case where at least one record failed (`PARTIALLY_PROCESSED` or `ERRORED`), an **error file** listing every bad row, its error code(s), and description is written to Amida Care's own outbound error folder, so they can see exactly what to fix.

### Stage 6 — Paperwork sent back to Amida Care

| Situation | Receipt file to Amida Care | Email sent |
|---|---|---|
| File rejected at file-level (Stage 1) | Rejection receipt (`FileRejected_<file>`) listing the file-level reason | "Submitted Monthly ESMF File Rejected" (or "...Resubmission File Rejected" for `RS_` files) |
| File fully successful, zero errors | Success receipt (`Success_<file>`) — **only issued when the file is 100% clean** | "Monthly ESMF File Successfully Processed" — no action needed |
| File has any error records — whether `PARTIALLY_PROCESSED` (below threshold) or `ERRORED` (at/above threshold) | Error file with every failed row/code/description | "Error found in Monthly ESMF File" — asks Amida Care to correct and resubmit before the due date |

A success receipt is deliberately **withheld** unless every single record in the file passed. Note that the error-notice email's wording does **not** currently distinguish between "partially successful, some good data already forwarded" and "errored, nothing forwarded at all" — both trigger the same email text. Amida Care would need to check the DB/dashboard (or wait to see whether the datalake shows the new data) to know which of the two actually happened. This is flagged as a business-communication gap in §10.

Every receipt sent out is also archived internally for audit purposes.

---

## 6. Worked example: a 100,000-record file, processed in batches

Amida Care submits `AMI_MDESMF_20260810093000.txt`: a valid header, followed by 100,000 member rows, terminated by `EOF`.

### Setup (batch 1 of 201)

- Filename and header pass validation; this isn't a duplicate file.
- Tenant lookup resolves Amida Care's `tenant_id`.
- A new tracking record is created: `mco_records` (parent) and `mco_record_details` (this attempt), both `status='INPROGRESS'`, counts at zero.
- Batch 1's own 499 data rows are validated along with everything else — say all 499 pass.

### Processing (batches 2–201)

Each subsequent batch validates its 500 (or, for the last batch, 1) rows independently, and the running totals grow batch by batch. No DB writes happen during this phase — the totals live in the pipeline's in-memory working state until the file is fully read (§4).

Two ways this can end, depending on how many of the 100,000 rows fail validation:

### Outcome A — 2,500 errors (2.5% error rate): below the 10% threshold

- **Final tally**: 97,500 valid, 2,500 errored, out of 100,000.
- **Outcome**: `PARTIALLY_PROCESSED` — error rate is below the 10% threshold.
- **Data movement**: the 97,500 valid records **are** forwarded to the state datalake inbound folder for that reporting month (archived internally as "processed"); the 2,500 error rows are written to an error file delivered to Amida Care's outbound `esmf/outbound/error/` folder (archived internally as "MCO error").
- **Amida Care receives**: an error file listing all 2,500 bad rows with their real row numbers, codes, and descriptions, plus the standard "errors found, please correct and resubmit" email. No success receipt.
- **Expected next step**: Amida Care resubmits a corrected 100,000-row file (see §7) referencing this original submission.

### Outcome B — 12,000 errors (12% error rate): at/above the 10% threshold

- **Final tally**: 88,000 valid, 12,000 errored, out of 100,000.
- **Outcome**: `ERRORED` — error rate is **at or above** the 10% threshold.
- **Data movement**: **none of the 88,000 valid records are forwarded to the datalake**, even though they individually passed every validation rule. Only the 12,000-row error file is delivered to Amida Care's outbound `esmf/outbound/error/` folder (and archived). The 88,000 valid rows remain sitting in the pipeline's internal S3 staging area (`mirth_processed/Success_<file>`) — not sent anywhere further — until a resubmission brings the file's error rate below 10%.
- **Amida Care receives**: the same "errors found, please correct and resubmit" email as Outcome A — the email itself does not say whether any data actually reached the datalake this time (see the communication gap noted in Stage 6).
- **Business impact**: a resubmission is not just "nice to have" here — it's required before *any* of this reporting cycle's data reaches the datalake, since the whole file was held back.

---

## 7. Re-submissions — how corrections are tracked

When Amida Care needs to correct a previously submitted file, they resend it with an `RS_` prefix, keeping the **original file's timestamp** embedded in the new filename plus a new timestamp for the resubmission itself:

`RS_AMI_MDESMF_<original timestamp>_<resubmission timestamp>.txt`

A resubmission — even a 100,000-row one — goes through the exact same batching mechanics described in §4: it's split into ~500-row batches, batch 1 does the one-time setup/lookup work, every batch validates its own rows, and only the final batch triggers the wrap-up and the one final DB update.

### How it's linked back to the original

The pipeline strips the `RS_` prefix and the trailing new timestamp to reconstruct the **original filename**, then looks up that original file's tracking record (`mco_records`, keyed by the parent file, independent of how many times it's been resubmitted).

**Tech/DB view — the lookup query:**

```sql
select batch_id, resubmitted_count, status, total_records, reporting_month, errored_count
from mco_data.mco_records
where root_file_name = <reconstructed original filename>
  and status not in ('FILE_REJECTED')
  and is_active = '1'
```

- No row returned (`batch_id` stays null) → treated as "original doesn't exist."
- A row is returned → its `status`, `reporting_month`, and `resubmitted_count` drive every rejection check below.

A second, separate lookup guards against replaying the exact same resubmission filename twice:

```sql
select batch_details_id from mco_data.mco_record_details
where current_file_name = <this resubmission's exact filename>
  and status not in ('FILE_REJECTED','MIRTH_INTERNAL_ERROR','LAMBDA_INTERNAL_ERROR')
  and is_active = '1'
```

Both queries are read-only — a rejection at this stage writes nothing to `mco_records`/`mco_record_details` for the resubmission itself (only the standard file-rejection destination logs the rejection outcome, same as any other file-level rejection). Both run once, on batch 1 of the resubmission, before any of its 201 batches of data rows are touched.

### Why a re-submission might be rejected outright (before any record is even looked at)

| Situation | Result |
|---|---|
| No original file found matching the reconstructed filename | Rejected — "resubmission of a file that doesn't exist" |
| Original file is still `INPROGRESS` (mid-processing, e.g. another batch of the same file hasn't finished, or a previous resubmission attempt is unresolved) | Rejected — avoids racing/conflicting updates to the same record |
| Original file was already **fully** processed (`FULLY_PROCESSED`) | Rejected — a fully clean file doesn't need or accept a correction |
| The resubmission's reporting month doesn't match the original's reporting month | Rejected — prevents a correction for one month being mis-applied to another |
| This exact resubmission filename has already been received and processed | Rejected as a duplicate resubmission |

In every rejection case above, Amida Care gets the same kind of rejection receipt + email described in Stage 6 — the resubmission subject line explicitly says "Resubmission File Rejected" so it's clear this isn't the original file bouncing. Note that neither `INPROGRESS` nor `ERRORED` blocks a resubmission by themselves in the way `FULLY_PROCESSED` does — an `ERRORED` original (Outcome B in §6) is exactly the case a resubmission is *for*.

### How a valid re-submission is tracked and processed

1. The **parent** tracking record (`mco_records`) for the original file has its **resubmission counter incremented by 1** — this is how many times this file family has been corrected.
2. The **previous attempt's** tracking record (`mco_record_details`) is marked inactive (superseded) — it's kept for history/audit, but is no longer "the current attempt."
3. A **brand-new attempt record** is created for this resubmission, starting again at `INPROGRESS`.
4. The resubmitted file's records are validated **from scratch, in fresh batches**, exactly like a first-time submission (§4, Stages 3–6 apply identically) — a resubmission is treated as a **complete replacement** of the member data for that reporting cycle, not a patch applied on top of the previous attempt's errors. Whatever passes/fails this time is what counts, including a fresh application of the 10% threshold rule against the resubmission's own totals.
5. Once the resubmission's final batch finishes processing, the parent record's overall counts (processed/errored/total) are updated to reflect this **latest attempt's** results.

**In short**: the system always keeps one "current" attempt per file family, remembers how many times it's been corrected, and treats each resubmission as a fresh, full validation pass through the same batching pipeline — while retaining every prior attempt's history for audit and troubleshooting.

### Tech/DB view: what a successful re-submission writes

| Step | Table & operation | Key columns |
|---|---|---|
| Resubmission counter bump | `mco_records` — UPDATE by `batch_id` | `resubmitted_count = resubmitted_count + 1`, `updated_on_utc = now()`. Note: `total_records`/`file_size_in_kb` on this parent row are **not** touched at this point — only counters. |
| Find the attempt being superseded | `mco_record_details` — SELECT | `batch_details_id where batch_id = ? and is_active='1' and status not in ('FILE_REJECTED','MIRTH_INTERNAL_ERROR','LAMBDA_INTERNAL_ERROR')` |
| Supersede it | `mco_record_details` — UPDATE | `is_active = '0'` on that `batch_details_id` (falls back to deactivating by `batch_id` if no single active row is found) |
| New attempt created | `mco_record_details` — INSERT | `current_file_name` = the `RS_...` filename, `batch_id` = same parent, `status='INPROGRESS'`, `total_records=0`, `is_active='1'` → new `batch_details_id` |
| Records re-validated across ~201 fresh batches, final batch reached | `mco_record_details` — UPDATE by the **new** `batch_details_id` | `status`, `processed_count`, `errored_count`, `total_records`, `qa_completed_time` — one UPDATE, using the fully accumulated totals, same write pattern as an original submission |
| Same event | `mco_records` — UPDATE by `batch_id` | `status` and `errored_count` refreshed; `processed_count` is **recomputed from the current `mco_record_details` row** (`total_records − errored_count` of the active attempt) — a resubmission's counts *replace* the prior attempt's on the parent record, they are not summed with it |
| Errors exist on the new attempt | `mco_record_error_logs` — INSERT | New row tied to the new `batch_details_id` |

Net effect: `mco_records` always reflects the **latest attempt's** outcome for the file family, while every historical attempt — original plus each resubmission — stays queryable in `mco_record_details` (the superseded ones simply carry `is_active='0'`), all sharing the same `batch_id`.

### A worked resubmission example, continuing the 100,000-record file

Continuing **Outcome B** from §6 (88,000 valid / 12,000 errored, `ERRORED`, nothing forwarded): Amida Care fixes the 12,000 flagged rows and resends the full 100,000-row file as `RS_AMI_MDESMF_20260810093000_20260812101500.txt`.

- The system matches this back to the original `AMI_MDESMF_20260810093000.txt` record, confirms it's still eligible for resubmission (status was `ERRORED`, not `FULLY_PROCESSED` or `INPROGRESS`; same reporting month), and accepts it.
- The resubmission counter on the parent record goes from 0 → 1.
- The original attempt's detail record is deactivated; a new detail record is created for this resubmission.
- The resubmitted file is split into its own ~201 batches and every one of the 100,000 rows is re-validated. Suppose the corrections bring the error count down to 800 (0.8%):
  - Error rate is now well below the 10% threshold → outcome is **`PARTIALLY_PROCESSED`**, and this time **99,200 records are forwarded to the datalake** — including the 88,000 that were valid (but withheld) on the first attempt, since a resubmission is a full replacement, not a delta.
  - Amida Care receives an error file for the remaining 800 rows and another "please correct and resubmit" email — but critically, the bulk of the data is no longer stuck.
- If Amida Care had instead fixed enough rows to hit **zero** errors, the outcome would be **`FULLY_PROCESSED`**, all 100,000 records go to the datalake, and Amida Care gets a success receipt and confirmation email for the first time in this file family's history.

**Tech/DB view of this resubmission** (`batch_id` stays the same throughout — only `mco_record_details` gets a new row):

| | `mco_records` | `mco_record_details` |
|---|---|---|
| Before resubmission | `resubmitted_count=0`, `status='ERRORED'`, `processed_count=88000`, `errored_count=12000` | Original attempt row: `current_file_name='AMI_MDESMF_20260810093000.txt'`, `status='ERRORED'`, `is_active='1'` |
| Resubmission accepted | `resubmitted_count=1`, `updated_on_utc=now()` | Original attempt row → `is_active='0'`; new row inserted: `current_file_name='RS_AMI_MDESMF_20260810093000_20260812101500.txt'`, `status='INPROGRESS'`, `is_active='1'` |
| After re-validation (800 errors remain) | `status='PARTIALLY_PROCESSED'`, `processed_count=99200`, `errored_count=800` | New attempt row updated: `status='PARTIALLY_PROCESSED'`, `processed_count=99200`, `errored_count=800`; a fresh `mco_record_error_logs` row is written for the 800 remaining bad rows, tied to the new `batch_details_id` |

---

## 8. Automatic reprocessing (system-triggered, not an Amida Care action)

Separately from Amida Care-initiated resubmissions, the pipeline has a **self-healing retry** for a specific set of known, transient technical errors (e.g. a momentary DB connectivity blip — the exact list is configured: connection refused/reset/timed-out, connection already closed, or the DB reporting too many concurrent clients). If such an error occurs while processing a file:

- The current attempt is abandoned without generating any receipts or emails to Amida Care.
- A short delay later, the pipeline automatically re-triggers processing of the *same* file from the top, as if it had just arrived again — meaning it will also re-run through batch 1's setup and all subsequent batches again.

This is invisible to Amida Care — it exists purely so that infrastructure hiccups don't require a human to manually resend a perfectly good file. For a 100,000-row file, this means a transient error partway through (say, during batch 140 of 201) results in the *entire file* being reprocessed from batch 1 again, not resumed from batch 140 — the pipeline has no concept of resuming a large file mid-way through.

---

## 9. Status reference (tracking database)

| Status | Meaning |
|---|---|
| `INPROGRESS` | File/attempt has been received and is currently being validated — this is the only status visible while any file, large or small, is still working through its batches |
| `FULLY_PROCESSED` | Every record in this attempt passed validation; full file sent to the datalake |
| `PARTIALLY_PROCESSED` | At least one record failed validation, but the error rate stayed **below** the configured threshold (10%); the valid subset was sent to the datalake |
| `ERRORED` | The error rate reached or exceeded the configured threshold (10%); **no** records — including the ones that individually passed — were sent to the datalake |
| `FILE_REJECTED` | The file itself was rejected before any records were validated (bad name/header/duplicate/etc.) |
| `MIRTH_INTERNAL_ERROR` | An unexpected technical error occurred while processing (not a data-quality issue) — logged for support to investigate |
| `LAMBDA_INTERNAL_ERROR` | The file was already flagged as malformed by the upstream file-transfer layer before this pipeline saw valid content |

---

## 10. Configuration values referenced in this document

The numbers used throughout §4 and §6 are not hard-coded in the pipeline logic — they're pulled from a configuration map, and could be changed by an administrator without a code deployment. The values below are what's actually deployed in the analyzed environment (`configuration-map/configuration_map_export.properties`):

| Setting | Deployed value | Effect |
|---|---|---|
| `chunkSize` | `500` | Number of file lines read per batch (§4) |
| `thresholdValue` | `10` (percent) | Error-rate threshold above which the whole file's valid data is withheld (`ERRORED`, §5–§6) |
| `allowMultipleFiles` | `true` | Whether more than one original file can be submitted for the same reporting month without being rejected as a duplicate |
| `startDate` / `endDate` | `20` / `4` | Day-of-month window used to decide which reporting-month folder a file's data is filed under (files landing on/after the 20th roll into the next month's folder) |

### Corrections and additions to the v2 error-code appendix

- **Threshold rule is active, not dead.** v2 incorrectly stated the threshold was configured at 100% (and therefore never triggered) based on a stale in-code comment. The deployed value is **10%**, and it directly determines whether a partially-bad file's good records reach the datalake at all (§5, `ERRORED` vs `PARTIALLY_PROCESSED`).
- **EC004** ("Missing EOF line") and **EC080** ("Submissions allowed only between the 20th and 4th of every month") are both defined in the error-code configuration but are **not wired up to any check in the current channel logic** — they will never actually be raised. (Note in particular that there is *no* enforced submission-window gate today, despite EC080's description — the 20th/4th dates are only used for reporting-month foldering, per the table above.)
- **EC031** ("MBR_LOB — must contain only digits") is defined in configuration but its corresponding check in the channel logic is commented out — confirmed dead, consistent with v2's note about the `MBR_LOB` field's validation being incomplete.
- Two fields (`EPop_Under6`, `EPop_Youth_JJ_FC`) and their error codes (`EC050`–`EC053`) were removed from validation per a prior program requirement change and remain inactive.

### Business-communication gap worth flagging

The "errors found" email sent to Amida Care (Stage 6) uses identical wording whether the file ended as `PARTIALLY_PROCESSED` (some data already reached the datalake) or `ERRORED` (no data reached the datalake at all, because the error rate crossed 10%). For a large file, this is a meaningful difference in urgency and impact that Amida Care currently cannot tell from the email alone.

---

## 11. Where things end up (summary)

| Content | Delivered to |
|---|---|
| Valid member records — full file (`FULLY_PROCESSED`) or the valid subset (`PARTIALLY_PROCESSED`) | State datalake inbound folder for the reporting month, plus an internal "processed" archive |
| Valid member records when the file is `ERRORED` (threshold exceeded) | **Nowhere** — held in internal staging only, until a resubmission drops the error rate below threshold |
| Error records (row/code/description) | Amida Care's own SFTP/S3 outbound `error` folder, plus an internal "MCO error" archive |
| Success receipt (only for a 100%-clean file) | Amida Care's own SFTP/S3 outbound `success` folder, plus an internal archive |
| Rejection receipt (file-level rejection) | Amida Care's own SFTP/S3 outbound `error` folder, plus an internal archive |
| Every outcome | A row/update in the tracking database, viewable on the monitoring dashboard, plus a confirmation/error/rejection email to Amida Care |

---

## Appendix — Full validation rule catalog (technical reference)

The tables below list every validation error code found in the channel logic, grouped by what triggers them. Descriptions are the actual configured text from `errorCodes` in the environment's configuration map.

### File-level error codes

| Code | Trigger |
|---|---|
| EC001 | File isn't a `.txt` file |
| EC002 | Filename doesn't match the required naming convention |
| EC003 | File (or this exact resubmission) has already been processed / is a duplicate |
| EC005 | Header row doesn't match the required 40-column layout |
| EC006 | Resubmission references an original file that doesn't exist, or the original is still in progress |
| EC081 | File has no data rows (empty after the header) |
| EC082 | A duplicate original file was submitted for a reporting month that already has one, and multiple files aren't allowed |
| EC083 | A data row has more fields than the header defines |
| EC084 | File was already flagged as invalid by the upstream transfer layer |
| EC085 | Post-completion record-count reconciliation mismatch; resubmission count is rolled back and file rejected |
| EC086 | Resubmission received, but the original file was already fully processed |
| EC087 | Resubmission's reporting month doesn't match the original's |
| EC088 | A second header row was found mid-file |

### Record-level error codes, by field

| Field | Required/empty | Format or length | Other rule |
|---|---|---|---|
| Member ID (CIN) | EC007 | EC008 (must be 8 chars) | EC009 (must be alphanumeric) |
| Last Name | EC010 | EC011 (max 50 chars) | — |
| First Name | EC012 | EC013 (max 50 chars) | EC096 (literal "NULL" not allowed) |
| Date of Birth | EC014 | EC015 (must be a valid calendar date) | EC094 (can't be a future date) |
| Address Line 1 | EC016 | EC017 (max 50 chars) | EC098 (literal "NULL" not allowed) |
| City | EC018 | EC019 (max 50 chars) | EC099 (literal "NULL" not allowed) |
| Postal Code | EC020 | EC021 (must be 5 digits) | EC022 (must be numeric) |
| County Code | EC023 | EC024 (must be 2 or 5 chars) | EC025 (must be numeric); EC095 (must be on the approved county list) |
| Plan ID | EC026 | EC027 (must be 5 digits) | EC028 (must be numeric); EC091 (can't be a reserved placeholder value) |
| Line of Business (LOB) code | EC029 | EC031 *(defined but dead — see §10)* | EC030 (must be "NULL") |
| Line of Business description | EC032 | — | EC033 (must be Mainstream / MLTCP / MAP) |
| Plan Enrollment Date | EC034 | EC035 (valid date) | EC093 (can't be after end of reporting month) |
| Disenrollment Date | EC036 | EC037 (valid date) | EC092 (can't be before start of reporting month) |
| High Utilizer flag | EC038 | — | EC039 (must be Y/N/U) |
| Health Home Enrolled flag | EC040 | — | EC041 (must be Y/N/U) |
| Other EPOP flag | EC042 | — | EC043 (must be Y/N/U) |
| Pregnancy/Postpartum flag | EC044 | — | EC045 (must be Y/N/U) |
| Under-18 Nutrition flag | EC089 | — | EC090 (must be Y/N/U) |
| Under-18 flag | EC046 | — | EC047 (must be Y/N/U) |
| Adult Criminal Justice flag | EC048 | — | EC049 (must be Y/N/U) |
| Chronic Conditions flag | EC054 | — | EC055 (must be Y/N/U) |
| Uncontrolled Asthma flag | EC056 | — | EC057 (must be Y/N/U) |
| Nutrition Therapy flag | EC058 | — | EC059 (must be Y/N/U) |
| Heat Illness Risk flag | EC060 | — | EC061 (must be Y/N/U) |
| Cold Illness Risk flag | EC062 | — | EC063 (must be Y/N/U) |
| Thermoregulation Risk flag | EC064 | — | EC065 (must be Y/N/U) |
| Healthy Homes flag | EC066 | — | EC067 (must be Y/N/U) |
| Nutrition Counseling flag | EC068 | — | EC069 (must be Y/N/U) |
| MTM/ILOS flag | EC070 | — | EC071 (must be Y/N/U) |
| OPWDD Waiver flag | EC072 | — | EC073 (must be Y/N/U) |
| TBI Waiver flag | EC074 | — | EC075 (must be Y/N/U) |
| NHTD Waiver flag | EC076 | — | EC077 (must be Y/N/U) |
| Children's Waiver flag | EC078 | — | EC079 (must be Y/N/U) |
| EPOP IDD flag | EC106 | — | EC107 (must be Y/N/U) |
| Home Phone (optional) | — | EC100 (must be 10 digits if present) | EC101 (must be numeric) |
| Cell Phone (optional) | — | EC102 (must be 10 digits) | EC103 (must be numeric) |
| Other Phone (optional) | — | EC104 (must be 10 digits) | EC105 (must be numeric) |
| PCP Name (optional) | — | EC108 (max 50 chars) | — |
| PCP NPI (optional) | — | EC109 (must be 10 digits) | EC110 (must be numeric) |
| PCP Phone (optional) | — | EC111 (must be 10 digits) | EC112 (must be numeric) |

### Known dead/inactive logic (does not affect current behavior, noted for future maintainers)

- `EC004` (missing EOF line) and `EC080` (submission-window gate) are defined in configuration but never raised by the current channel logic.
- `EC031` (MBR_LOB digit check) is defined in configuration but its corresponding validation is commented out in the channel logic.
- Two fields (`EPop_Under6`, `EPop_Youth_JJ_FC`) and their error codes (`EC050`–`EC053`) were removed from validation per a prior program requirement change and are no longer active.
- A few edge-case error codes are constructed in the code but never actually attached to a record's error list (effectively unused).
- The error-rate threshold (`thresholdValue`) is **not** dead — see the correction in §10. This is the one item v2 got wrong; it's called out again here to avoid the mistake propagating.
