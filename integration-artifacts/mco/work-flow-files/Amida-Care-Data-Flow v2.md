# ESMF File Ingestion: Business Data Flow (Using Amida Care channel - AMI)
Source: `mco-channel-files/nexus-sandbox/Amida Care.xml` (Mirth Connect channel "Amida Care", MCO short code **AMI**).
This document explains **what happens to a submitted file and its records, in business terms**, from the moment Amida Care drops a monthly ESMF (Expanded Special Population/"ESMF") file until it lands with the state datalake and Amida Care gets a confirmation back. Technical detail (SQL, code, server names) is kept to the minimum needed to make the business rule unambiguous.

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

---

## 3. High-level flow

```mermaid
flowchart TD
    A[Amida Care drops file on S3] --> B{Filename & header valid?}
    B -- No --> R[File rejected — no records processed]
    B -- Yes --> C{New file, resubmission,\nor duplicate?}
    C -- Duplicate / conflicting resubmission --> R
    C -- New or valid resubmission --> D[Validate every member record]
    D --> E[Split: valid records vs. error records]
    E --> F[Write Success file + Error file to staging]
    F --> G{Any errors in the file?}
    G -- No errors --> H[Send full file to state datalake]
    G -- Some errors --> I[Send only the good records to state datalake]
    G -- All records failed --> J[Nothing sent to datalake]
    H --> K[Success receipt + confirmation email to Amida Care]
    I --> L[Error receipt + "please correct & resubmit" email]
    J --> L
    R --> M[Rejection receipt + rejection email to Amida Care]
    K --> N[(Tracking DB updated: FULLY_PROCESSED)]
    L --> O[(Tracking DB updated: PARTIALLY_PROCESSED)]
    M --> P[(Tracking DB updated: FILE_REJECTED)]
```

The pipeline processes the file in chunks (batches of rows) rather than all at once, but from a business standpoint the whole file is treated as one submission with one final outcome.

---

## 4. Stage-by-stage

### Stage 1 — File-level gatekeeping (before any member data is looked at)

The very first thing checked is whether the **file itself** is acceptable. Any failure here rejects the entire file — no member records are validated, no data goes anywhere except a rejection notice back to Amida Care.

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

**Outcome of a file-level rejection**: a rejection receipt file is written back to Amida Care's own outbound folder (`.../esmf/outbound/error/FileRejected_<filename>`), archived internally, an email alert goes out to Amida Care, and the attempt is logged in the tracking DB with status **`FILE_REJECTED`**. No member records are validated or forwarded anywhere.

### Stage 2 — Is this a new file or a re-submission? (see §6 for the full re-submission flow)

If the filename doesn't start with `RS_`, it's treated as a brand-new monthly file: a new tracking record is created with status **`INPROGRESS`**.

If it does start with `RS_`, the pipeline looks up the *original* file it's correcting and applies the re-submission rules described in §6.

### Stage 3 — Record-by-record validation

Once the file itself is accepted, **every single member row** is validated independently against business rules, for example:
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

- All **valid records** are collected into a "Success" data set.
- All **error records** are collected into an "Error" data set, each one tagged with its row number, the error code(s), and a plain-language description of what was wrong.

Both sets are staged (in AWS S3) as the file is processed, so that even a very large file can be handled in manageable chunks without losing track of what's succeeded or failed so far.

### Stage 5 — Deciding the outcome and where data goes

Once the entire file has been read, the pipeline looks at the totals and decides one of three outcomes:

| Outcome | When it happens | What's sent to the state datalake | Tracking DB status |
|---|---|---|---|
| **Fully successful** | Every record passed validation, zero errors | The complete file | `FULLY_PROCESSED` |
| **Partially successful** | Some records failed, but the error rate stays **below** the configured 10% threshold | Only the valid records (bad records are held back) | `PARTIALLY_PROCESSED` |
| **Errored — threshold exceeded** | The error rate reaches or exceeds the configured 10% threshold (this includes, but isn't limited to, every record failing) | **Nothing** — even records that individually passed validation are withheld | `ERRORED` |

In every case where at least one record failed, an **error file** listing every bad row, its error code(s), and description is written to Amida Care's own outbound error folder, so they can see exactly what to fix — this happens whether the file was "partially successful" or "all failed."

> **Note**: configuration `thresholdValue = 10` — this rule **is active**. Any file where 10% or more of its records fail validation gets **none** of its data forwarded to the datalake, not even the valid records, until a corrected resubmission brings the error rate below 10%. In that case the tracking DB status is `ERRORED` rather than `PARTIALLY_PROCESSED`. See [v3 of this document](Amida-Care-Data-Flow%20v3.md) for the full explanation and a 100,000-record worked example of both outcomes.

### Stage 6 — Paperwork sent back to Amida Care

| Situation | Receipt file to Amida Care | Email sent |
|---|---|---|
| File rejected at file-level (Stage 1) | Rejection receipt (`FileRejected_<file>`) listing the file-level reason | "Submitted Monthly ESMF File Rejected" (or "...Resubmission File Rejected" for `RS_` files) |
| File fully successful, zero errors | Success receipt (`Success_<file>`) — **only issued when the file is 100% clean** | "Monthly ESMF File Successfully Processed" — no action needed |
| File has any error records (partial or total failure) | Error file with every failed row/code/description | "Error found in Monthly ESMF File" — asks Amida Care to correct and resubmit before the due date |

A success receipt is deliberately **withheld** unless every single record in the file passed — a partially-successful file only gets the error notice, signaling that a correction/resubmission is still expected.

Every receipt sent out is also archived internally for audit purposes.

---

## 5. Worked example: a 10-record file, some good, some bad

Amida Care submits `AMI_MDESMF_20260810093000.txt` with a valid header and 10 member rows. Suppose:

- Rows 1, 2, 4, 5, 6, 8, 9 pass validation (7 valid records)
- Row 3 is missing the Member ID → error code for "Member ID missing"
- Row 7 has an invalid county code → error code for "County code not recognized"
- Row 10 has a future date of birth → error code for "Future date of birth not allowed"

**What happens:**

1. **File-level checks pass** — filename and header are correct, this isn't a duplicate. A new record is created in the tracking DB: `mco_records` (parent, one row per file family) and `mco_record_details` (this specific attempt), both starting at status `INPROGRESS`, `total_records` eventually set to 10.
2. **Every row is validated.** 7 rows pass, 3 fail (rows 3, 7, 10) — each failed row is logged with its row number, error code, and description.
3. **Split and staged**: the 7 good rows go into a "Success" data set; the 3 bad rows (with their error annotations) go into an "Error" data set.
4. **Outcome = Partially successful** (7 of 10 good). Tracking DB is updated: `processed_count = 7`, `errored_count = 3`, `total_records = 10`, `status = PARTIALLY_PROCESSED`. The 3 validation failures are also written to the error-log table (for the monitoring dashboard) with a summary of which error codes fired and on which rows.
5. **Data movement**:
   - The **7 valid records** are forwarded to the state datalake inbound folder for that reporting month, and archived internally as "processed."
   - The **3 error records** are written into an error file and delivered to Amida Care's own outbound `esmf/outbound/error/` folder, and archived internally as "MCO error."
6. **Amida Care receives**: an error file listing rows 3, 7, 10 with their specific reasons, plus an email: *"Errors were found in your Monthly ESMF File... Please correct the errors and resubmit before the due date."* They do **not** receive a success receipt, because the file wasn't 100% clean.
7. Amida Care is now expected to send a **re-submission** containing corrected data (see §6) — typically just the corrected/complete file again, named with the `RS_` prefix referencing the original timestamp.

If instead all 10 rows had passed, Amida Care would have received a success receipt and a "successfully processed" email, and no error file would exist at all.

If all 10 rows had failed, no data would go to the datalake at all, but the error file/email would go out exactly as above — the file is still logged as `PARTIALLY_PROCESSED` (0 of 10 processed) rather than fully rejected, because the file itself was structurally valid; it's the *content* that failed.

### Tech/DB view of this example

Three tables track everything, all under the `mco_data` schema:

| Table | Grain | Purpose |
|---|---|---|
| `mco_records` | One row per **file family** (`root_file_name`) — created once, reused across every resubmission of that file | The "parent" record: current overall status and counts for this reporting-cycle submission |
| `mco_record_details` | One row per **physical attempt** — the original file and each resubmission each get their own row, linked to the parent via `batch_id` | Tracks exactly which physical file caused which outcome; `is_active='1'` marks the current attempt, `'0'` marks a superseded one |
| `mco_record_error_logs` | One row per attempt **that had errors** | Holds the actual row/code/description detail (`error_json`) behind the counts above |

Walking the same 7-valid/3-error example through those tables:

| Step | Table & operation | Key columns written |
|---|---|---|
| File arrives, passes filename/header checks, not a duplicate | `mco_records` — INSERT | `root_file_name='AMI_MDESMF_20260810093000.txt'`, `status='INPROGRESS'`, `total_records=0`, `resubmitted_count=0`, `tenant_id`, `reporting_month`, `is_active='1'` → generates `batch_id` |
| Same event | `mco_record_details` — INSERT | `current_file_name` = same filename, `batch_id` = FK from above, `status='INPROGRESS'`, `total_records=0`, `is_active='1'` → generates `batch_details_id` |
| All 10 rows validated (7 pass, 3 fail) | *(no DB write yet)* | Running totals (`processed=7`, `errored=3`) and the 3 rows' error codes/descriptions accumulate in memory across batches until the last batch of the file |
| Final batch — outcome computed as `PARTIALLY_PROCESSED` | `mco_records` — UPDATE by `batch_id` | `status='PARTIALLY_PROCESSED'`, `processed_count=7`, `errored_count=3`, `total_records=10`, `file_size_in_kb`, `qa_completed_time=now()`, `updated_on_utc=now()` |
| Same event | `mco_record_details` — UPDATE by `batch_details_id` | Same fields as above, scoped to this one attempt |
| Errors exist (`errored_count=3`) | `mco_record_error_logs` — INSERT | `batch_id`, `batch_details_id`, `tenant_id`, `error_type_id`='FIELD_VALIDATION_ERROR', `error_json` = the row/code/description list for rows 3, 7, 10, `is_active='1'` |
| Valid records sent to the datalake, error file sent to Amida Care | `mco_records` **and** `mco_record_details` — UPDATE | `sent_to_datalake_time=now()`, `sent_to_mco_time=now()`, `updated_on_utc=now()` on both, scoped by `batch_id` / `batch_details_id` respectively |

Nothing is ever deleted — a fully clean file would follow the identical write pattern except `errored_count=0`, `status='FULLY_PROCESSED'`, and no `mco_record_error_logs` row at all.

---

## 6. Re-submissions — how corrections are tracked

When Amida Care needs to correct a previously submitted file, they resend it with an `RS_` prefix, keeping the **original file's timestamp** embedded in the new filename plus a new timestamp for the resubmission itself:

`RS_AMI_MDESMF_<original timestamp>_<resubmission timestamp>.txt`

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

Both queries are read-only — a rejection at this stage writes nothing to `mco_records`/`mco_record_details` for the resubmission itself (only the standard file-rejection destination logs the rejection outcome, same as any other file-level rejection).

### Why a re-submission might be rejected outright (before any record is even looked at)

| Situation | Result |
|---|---|
| No original file found matching the reconstructed filename | Rejected — "resubmission of a file that doesn't exist" |
| Original file is still `INPROGRESS` (mid-processing, e.g. another batch of the same file hasn't finished, or a previous resubmission attempt is unresolved) | Rejected — avoids racing/conflicting updates to the same record |
| Original file was already **fully** processed (`FULLY_PROCESSED`) | Rejected — a fully clean file doesn't need or accept a correction |
| The resubmission's reporting month doesn't match the original's reporting month | Rejected — prevents a correction for one month being mis-applied to another |
| This exact resubmission filename has already been received and processed | Rejected as a duplicate resubmission |

In every rejection case above, Amida Care gets the same kind of rejection receipt + email described in §4/Stage 6 — the resubmission subject line explicitly says "Resubmission File Rejected" so it's clear this isn't the original file bouncing.

### How a valid re-submission is tracked and processed

1. The **parent** tracking record (`mco_records`) for the original file has its **resubmission counter incremented by 1** — this is how many times this file family has been corrected.
2. The **previous attempt's** tracking record (`mco_record_details`) is marked inactive (superseded) — it's kept for history/audit, but is no longer "the current attempt."
3. A **brand-new attempt record** is created for this resubmission, starting again at `INPROGRESS`.
4. The resubmitted file's records are validated **from scratch**, exactly like a first-time submission (Stages 3–6 above apply identically) — a resubmission is treated as a **complete replacement** of the member data for that reporting cycle, not a patch applied on top of the previous attempt's errors. Whatever passes/fails this time is what counts; the outcome (successful / partially successful) and receipts/emails are generated the same way as for an original file.
5. Once the resubmission finishes processing, the parent record's overall counts (processed/errored/total) are updated to reflect this **latest attempt's** results.

**In short**: the system always keeps one "current" attempt per file family, remembers how many times it's been corrected, and treats each resubmission as a fresh, full validation pass — while retaining every prior attempt's history for audit and troubleshooting.

### Tech/DB view: what a successful re-submission writes

| Step | Table & operation | Key columns |
|---|---|---|
| Resubmission counter bump | `mco_records` — UPDATE by `batch_id` | `resubmitted_count = resubmitted_count + 1`, `updated_on_utc = now()`. Note: `total_records`/`file_size_in_kb` on this parent row are **not** touched at this point — only counters. |
| Find the attempt being superseded | `mco_record_details` — SELECT | `batch_details_id where batch_id = ? and is_active='1' and status not in ('FILE_REJECTED','MIRTH_INTERNAL_ERROR','LAMBDA_INTERNAL_ERROR')` |
| Supersede it | `mco_record_details` — UPDATE | `is_active = '0'` on that `batch_details_id` (falls back to deactivating by `batch_id` if no single active row is found) |
| New attempt created | `mco_record_details` — INSERT | `current_file_name` = the `RS_...` filename, `batch_id` = same parent, `status='INPROGRESS'`, `total_records=0`, `is_active='1'` → new `batch_details_id` |
| Records re-validated, final batch reached | `mco_record_details` — UPDATE by the **new** `batch_details_id` | `status`, `processed_count`, `errored_count`, `total_records`, `qa_completed_time` — same write pattern as an original submission |
| Same event | `mco_records` — UPDATE by `batch_id` | `status` and `errored_count` refreshed; `processed_count` is **recomputed from the current `mco_record_details` row** (`total_records − errored_count` of the active attempt) — a resubmission's counts *replace* the prior attempt's on the parent record, they are not summed with it |
| Errors exist on the new attempt | `mco_record_error_logs` — INSERT | New row tied to the new `batch_details_id`, same shape as in §5 |

Net effect: `mco_records` always reflects the **latest attempt's** outcome for the file family, while every historical attempt — original plus each resubmission — stays queryable in `mco_record_details` (the superseded ones simply carry `is_active='0'`), all sharing the same `batch_id`.

### A worked resubmission example

Continuing the example from §5 (7 valid / 3 error records): Amida Care fixes rows 3, 7, and 10 and resends the full 10-row file as `RS_AMI_MDESMF_20260810093000_20260812101500.txt`.

- The system matches this back to the original `AMI_MDESMF_20260810093000.txt` record, confirms it's still eligible for resubmission (not fully processed, not in-progress, same reporting month), and accepts it.
- The resubmission counter on the parent record goes from 0 → 1.
- The original attempt's detail record is deactivated; a new detail record is created for this resubmission.
- All 10 rows are re-validated. If the corrections were done properly, all 10 now pass → outcome is **Fully successful**, all 10 records go to the datalake, and Amida Care receives a success receipt and confirmation email this time.
- If Amida Care only fixed 2 of the 3 issues, the outcome would again be **Partially successful** (9 of 10), and the cycle in §5 repeats — another error file/email, another resubmission expected.

**Tech/DB view of this resubmission** (`batch_id` stays the same throughout — only `mco_record_details` gets a new row):

| | `mco_records` | `mco_record_details` |
|---|---|---|
| Before resubmission | `resubmitted_count=0`, `status='PARTIALLY_PROCESSED'`, `processed_count=7`, `errored_count=3` | Original attempt row: `current_file_name='AMI_MDESMF_20260810093000.txt'`, `status='PARTIALLY_PROCESSED'`, `is_active='1'` |
| Resubmission accepted | `resubmitted_count=1`, `updated_on_utc=now()` | Original attempt row → `is_active='0'`; new row inserted: `current_file_name='RS_AMI_MDESMF_20260810093000_20260812101500.txt'`, `status='INPROGRESS'`, `is_active='1'` |
| After re-validation (all 10 now pass) | `status='FULLY_PROCESSED'`, `processed_count=10`, `errored_count=0` | New attempt row updated: `status='FULLY_PROCESSED'`, `processed_count=10`, `errored_count=0`; no `mco_record_error_logs` row written this time |

If instead only 2 of 3 issues were fixed, the same table would show `mco_records.status='PARTIALLY_PROCESSED'`, `processed_count=9`, `errored_count=1`, and a fresh `mco_record_error_logs` row for the one remaining bad row — tied to the new `batch_details_id`, not the original one.

---

## 7. Automatic reprocessing (system-triggered, not an Amida Care action)

Separately from Amida Care-initiated resubmissions, the pipeline has a **self-healing retry** for a specific set of known, transient technical errors (e.g. a momentary DB connectivity blip). If such an error occurs while processing a file:

- The current attempt is abandoned without generating any receipts or emails to Amida Care.
- A short delay later, the pipeline automatically re-triggers processing of the *same* file from the top, as if it had just arrived again.

This is invisible to Amida Care — it exists purely so that infrastructure hiccups don't require a human to manually resend a perfectly good file.

---

## 8. Status reference (tracking database)

| Status | Meaning |
|---|---|
| `INPROGRESS` | File/attempt has been received and is currently being validated |
| `FULLY_PROCESSED` | Every record in this attempt passed validation; full file sent to the datalake |
| `PARTIALLY_PROCESSED` | At least one record failed validation; only the valid subset (possibly zero records) was sent to the datalake |
| `FILE_REJECTED` | The file itself was rejected before any records were validated (bad name/header/duplicate/etc.) |
| `MIRTH_INTERNAL_ERROR` | An unexpected technical error occurred while processing (not a data-quality issue) — logged for support to investigate |
| `LAMBDA_INTERNAL_ERROR` | The file was already flagged as malformed by the upstream file-transfer layer before this pipeline saw valid content |

---

## 9. Where things end up (summary)

| Content | Delivered to |
|---|---|
| Valid member records (full file, or the valid subset of a partial file) | State datalake inbound folder for the reporting month, plus an internal "processed" archive |
| Error records (row/code/description) | Amida Care's own SFTP/S3 outbound `error` folder, plus an internal "MCO error" archive |
| Success receipt (only for a 100%-clean file) | Amida Care's own SFTP/S3 outbound `success` folder, plus an internal archive |
| Rejection receipt (file-level rejection) | Amida Care's own SFTP/S3 outbound `error` folder, plus an internal archive |
| Every outcome | A row/update in the tracking database, viewable on the monitoring dashboard, plus a confirmation/error/rejection email to Amida Care |

---

## Appendix — Full validation rule catalog (technical reference)

The tables below list every validation error code found in the channel logic, grouped by what triggers them. Exact wording of each error description lives in the pipeline's configuration (`errorCodes` map), not in the channel logic itself — the table shows the underlying rule.

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
| Line of Business (LOB) code | EC029 | — | EC030 |
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

- the deployed `thresholdValue` is **10%**, so this rule is active. A file with a 10%+ error rate is marked `ERRORED` and has **none** of its records forwarded to the datalake, including the valid ones. (This entry was wrong in the original version of this document; kept here, struck through, so the correction is traceable. See [v3](Amida-Care-Data-Flow%20v3.md) for the full writeup.)
- Two fields (`EPop_Under6`, `EPop_Youth_JJ_FC`) and their error codes were removed from validation per a prior program requirement change and are no longer active.
- A few edge-case error codes are constructed in the code but never actually attached to a record's error list (effectively unused).
