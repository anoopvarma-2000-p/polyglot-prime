# MCO ESMF test files

Generated test input files for the AET ESMF channel (`Aetna.xml`), validated against the
real validation rules in that channel (header/EOF structure, field formats, and the
date-window rules `EC092`/`EC093` tied to the current reporting month).

Reporting month used when generating these files: **July 2026** (2026-07-01 to 2026-07-31),
computed from the `startDate=20`/`endDate=4` cutoff in `config-map-export-example.properties`
applied to 2026-07-10. `MBR_CONT_PLAN_ENROLL_DT` values are on/before 2026-07-31;
`MBR_PROS_DISENROLL_DT` values are on/after 2026-07-01. All other fields (MBR_ID, county
codes, plan IDs, LOB_DESC, Y/N/U flags, phone/PCP formats) follow the rules documented in
`Aetna-channel-analysis.md`.

## All-success (expect processed = row count, errored = 0)

| File | Rows |
|---|---|
| AET_MDESMF_20260710090000.txt | 12 |
| AET_MDESMF_20260710091500.txt | 10 |
| AET_MDESMF_20260710093000.txt | 14 |
| AET_MDESMF_20260710094500.txt | 15 |

## All-error (expect processed = 0, errored = row count)

| File | Rows | Scenario |
|---|---|---|
| AET_MDESMF_20260710100000.txt | 10 | Every row has `MBR_DOB` in the future → `EC094` |
| AET_MDESMF_20260710101500.txt | 12 | Every row has `MBR_PROS_DISENROLL_DT = 19700823` (replicates the real production bug seen in `AET_MDESMF_20250926155920.txt`) → `EC092` |
| AET_MDESMF_20260710103000.txt | 13 | Grab-bag: each row fails a *different* single rule (county code, banned plan ID, malformed plan ID, invalid LOB_DESC, bad postal code, invalid Y/N/U flag, MBR_ID wrong length, MBR_ID non-alphanumeric, bad phone format, bad PCP_NPI format, literal `NULL` address, literal `NULL` city, empty EPOP_IDD) |

## Mixed (some valid, some error)

| File | Rows | Expected processed / errored | Error rows |
|---|---|---|---|
| AET_MDESMF_20260710110000.txt | 12 | 8 / 4 | row 4 (future DOB), row 7 (banned PLAN_ID), row 10 (bad county), row 13 (stale disenroll date) |
| AET_MDESMF_20260710111500.txt | 10 | 5 / 5 | row 3 (bad postal), row 5 (bad LOB_DESC), row 7 (bad flag value), row 9 (bad MBR_ID length), row 11 (bad phone) |
| AET_MDESMF_20260710113000.txt | 15 | 11 / 4 | row 3 (empty EPOP_IDD), row 6 (future enroll date), row 10 (bad PCP_NPI), row 14 (literal NULL address) |

## Low-error-rate (for PARTIALLY_PROCESSED status)

`config-map-export-example.properties` ships with `thresholdValue` blank, which causes
`thresholdExceeced()` in `Aetna.xml` (`(totalRecords * limit) / 100 <= errorRecords`) to
evaluate true for every file — even ones with 0 errors — because the effective limit
resolves to 0. Every file's `mco_records.status` ends up `ERRORED` regardless of content.
Set `thresholdValue = 10` (10%) in the real configuration map to fix this; with a positive
threshold, 0-error files correctly resolve to `FULLY_PROCESSED`.

| File | Rows | Expected processed / errored | Error rows | Expected status @ thresholdValue=10 |
|---|---|---|---|---|
| AET_MDESMF_20260710114500.txt | 15 | 14 / 1 (6.7%) | row 9 (bad flag value) | `PARTIALLY_PROCESSED` (below the 10% threshold) |

Note: at a production-sensible threshold like 10%, the three "Mixed" files above (27-50%
error rates) will still resolve to overall `ERRORED` status — that's expected, since that
error rate is high enough to warrant flagging the whole submission. Only this low-error-rate
file is under the threshold and should show `PARTIALLY_PROCESSED`.

All files were verified with a local re-implementation of the channel's validation rules
before being committed; see the row numbers above for the exact expected failures per file.

## Resubmission files (`RS_` prefix)

Per §5 (Resubmission Procedures) of the RRHIO ESMF File Transmission Specification
(`1.MCO.Phase.1.-.RRHIO.ESMF.File.Transmission.Specification.Document_v7.1.docx`), a
resubmission must be named `RS_{OriginalFileName}_{YYYYMMDDhhmmss}.txt` and must contain
**the same number of records as the error rows** found in the original file's error
response — no more, no fewer, even if some errors are left uncorrected — or the whole
resubmission is rejected (`EC085`). Each file below pairs with one of the all-error/mixed/
low-error-rate originals above and carries exactly its error-row count, corrected.

Reporting month used when validating these files: **August 2026** (2026-08-01 to
2026-08-31), computed the same way as above but applied to 2026-07-30 (`calculateReportingMonth()`
in `Aetna.xml` rolls the month forward once `date >= startDate`). A few rows whose
`MBR_PROS_DISENROLL_DT` was valid under the July window (e.g. the "Brooksbank"/"Okafor"
template rows, dated `20260715`) needed bumping forward in addition to the originally
-injected error, since `20260715` no longer satisfies "not before the reporting month
start" once that start rolls to `2026-08-01`. This is the same moving-target caveat noted
in `generate-mco-test-files.md` for the original files — re-verify against
`config-map-export-example.properties` and today's date before trusting these as
all-success if much time has passed since generation.

| Resubmission file | Rows | Pairs with (original) | Corrects |
|---|---|---|---|
| RS_AET_MDESMF_20260710100000_20260711090000.txt | 10 | AET_MDESMF_20260710100000.txt | All 10 `MBR_DOB` future-date errors (`EC094`) restored to each member's real past DOB; row 6 (Brooksbank) disenroll date also bumped past 2026-08-01 |
| RS_AET_MDESMF_20260710101500_20260711091500.txt | 12 | AET_MDESMF_20260710101500.txt | All 12 `MBR_PROS_DISENROLL_DT = 19700823` errors (`EC092`) bumped to `20270823` |
| RS_AET_MDESMF_20260710103000_20260711093000.txt | 13 | AET_MDESMF_20260710103000.txt | Each row's single injected error fixed in place (county, plan ID x2, LOB_DESC, postal code, flag value + stale disenroll date, MBR_ID x2, phone, PCP_NPI, NULL address, NULL city, empty EPOP_IDD) |
| RS_AET_MDESMF_20260710110000_20260711100000.txt | 4 | AET_MDESMF_20260710110000.txt | row 4 (future DOB), row 7 (banned PLAN_ID + stale disenroll date), row 10 (bad county), row 13 (stale disenroll date) |
| RS_AET_MDESMF_20260710111500_20260711101500.txt | 5 | AET_MDESMF_20260710111500.txt | row 3 (bad postal), row 5 (bad LOB_DESC), row 7 (bad flag value + stale disenroll date), row 9 (bad MBR_ID length), row 11 (bad phone) |
| RS_AET_MDESMF_20260710113000_20260711103000.txt | 4 | AET_MDESMF_20260710113000.txt | row 3 (empty EPOP_IDD), row 6 (future enroll date), row 10 (bad PCP_NPI), row 14 (literal NULL address) |
| RS_AET_MDESMF_20260710114500_20260711104500.txt | 1 | AET_MDESMF_20260710114500.txt | row 9 (bad flag value) |

All seven were re-validated with a local re-implementation of the channel's field rules
(county/FIPS list, plan ID, LOB_DESC, phone/PCP formats, Y/N/U flags, and the August-2026
enroll/disenroll window) and confirmed to produce zero remaining field errors — i.e. each
is a fully-corrected resubmission that should resolve to `FULLY_PROCESSED` once the count
and naming checks pass. Byte-identical copies (content is MCO-agnostic; only the MCO code
in the filename differs, per the shared-validation-logic note above) live in
`test-files/AMI/` as the canonical templates `generate-mco-test-files.sh` clones for every
other MCO.
