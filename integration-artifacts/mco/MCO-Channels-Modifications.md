# Aetna, Amida Care & Anthem — Modifications for Testability

**Files:** [`Aetna.xml`](Aetna.xml), [`Amida Care.xml`](Amida%20Care.xml), [`Anthem.xml`](Anthem.xml)
**Reference / ground truth:** [`Aetna-original.xml`](Aetna-original.xml) — the pre-fix export of the Aetna channel, kept alongside `Aetna.xml` (the fixed, confirmed-working version) specifically so the exact set of changes could be verified rather than inferred.
**Baseline:** these three channels are near-identical per-MCO clones sharing the same script body (only `mco_code` / `mco_sftp_folder` / channel identity differ). `Aetna.xml` already had a set of bug fixes and local-test-path changes applied and was confirmed working; those same changes were ported to `Amida Care.xml` and `Anthem.xml`.
**Method:**
1. Diffed `Aetna-original.xml` against `Aetna.xml` directly (51 hunks) — this is the authoritative list of what changing Aetna from "as originally exported" to "fixed and testable" actually involved, and is what every section below is based on.
2. Cross-checked by separately diffing `Aetna.xml` against `Amida Care.xml` (228 diff lines) and `Anthem.xml` (230 diff lines) — `Amida Care.xml` and `Anthem.xml` were themselves confirmed identical to each other apart from identity fields, and their pre-fix code matched `Aetna-original.xml`'s pre-fix code byte-for-byte at every changed location (confirmed by clean `patch --fuzz=0` application).
3. Every hunk was classified as **identity** (channel id/name/revision, `mco_code`, `mco_sftp_folder` — left untouched per channel), **bug fix** (ported verbatim into Amida/Anthem), or **local-test path** (present in Aetna, ported into Amida/Anthem with a per-channel folder name rather than copied verbatim).

---

## 1. Bug fixes

These fix real defects that would surface once the channels actually process files, independent of any environment/testing concerns. Confirmed present in `Aetna.xml` but absent from `Aetna-original.xml`; the same fixes were applied to `Amida Care.xml` and `Anthem.xml` (both files had the identical pre-fix code, confirmed by clean `patch --fuzz=0` application).

### 1.1 Empty-file EOF check could index out of bounds

**Where:** source transformer, first-batch file validation (~line 428).

After stripping the header line with `.shift()`, the code checked `trimmedLines[0]` without first confirming the array wasn't empty — a file containing only a header row (no `EOF` marker, no data) would throw instead of being cleanly rejected.

```diff
- if (trimmedLines && trimmedLines[0].toString().toLowerCase().trim().equals("eof")) {
+ if (trimmedLines && (trimmedLines.length === 0 || trimmedLines[0].toString().toLowerCase().trim().equals("eof"))) {
```

### 1.2 `undefined` bound as a JDBC parameter masked real errors

**Where:** ~10 destination-level `catch` blocks across the channel (Original File Writer, Success Data, Error Data, Stream-to-SFTP, DB Calls, Rejection Receipt, Success Receipt, both Archive destinations, File-rejection-in-DB).

When an error was thrown before `batch_id`/`mco_id` (or `batch_details_id`) had been assigned in that scope, the catch handler still tried to log a `MIRTH_INTERNAL_ERROR` audit row using those variables. Binding a JS `undefined` (as opposed to `null`) to a JDBC `?::bigint` parameter throws its own `TypeError`, which replaced/hid the original error in the logs.

Fix: capture the value defensively before use, falling back to `null`.

```diff
+ //$('batch_id')/$('mcoId') resolve to "" (not null) when unset, which fails the ?::bigint cast below
+ var mirthBatchId = $("batch_id") || null;
+ var mirthMcoId = $("mcoId") || null;
  ...
- [$('originalFilename'), 0, 0, $('batch_id'), $('mcoId')]
+ [$('originalFilename'), 0, 0, mirthBatchId, mirthMcoId]
  ...
- mirthBatchId,   // (error_log_insert bind array)
+ $("batch_id"),
```

One earlier catch block (inside the shared error-logging helper, ~line 2123) used an equivalent but distinct guard style:

```diff
+ //batch_id/mco_id may still be undefined if the original error occurred before either was assigned;
+ //binding a JS "undefined" (as opposed to null) as a JDBC param throws its own TypeError and masks the real error
+ if (typeof fileName === "undefined") { fileName = $("originalFilename"); }
+ if (typeof batch_id === "undefined") { batch_id = null; }
+ if (typeof mco_id === "undefined") { mco_id = null; }
```

### 1.3 Falsy-zero bug in per-batch counter accumulation

**Where:** destination 2, "Original File Writer / Put data in global map" (~line 2450).

The counters (`recordsCount`, `totalFileSize`, `totalErrors`, `totalProcess`) were accumulated with `if ($("no_of_records")) { ... }`. In JavaScript, `0` is falsy, so a batch that legitimately contributed `0` errors or `0` processed rows skipped the block entirely — and if that happened on the *first* batch, the counter was never initialized at all, leaving it as `""` rather than `0` for downstream `?::bigint` binds.

```diff
  var recordCount = globalChannelMap.get("recordsCount");
+ if (recordCount === null || recordCount === undefined) { recordCount = 0; }
- if ($("no_of_records")) {
-   recordCount += $("no_of_records");
-   globalChannelMap.put("recordsCount", recordCount);
- }
+ if ($("no_of_records") !== null && $("no_of_records") !== undefined && $("no_of_records") !== "") {
+   recordCount += Number($("no_of_records"));
+ }
+ globalChannelMap.put("recordsCount", recordCount);
```

Applied identically to `totalFileSize`, `totalErrors`, and `totalProcess`. `globalChannelMap.put(...)` now always runs (not just on a truthy delta), and `Number(...)` coercion guards against string concatenation bugs.

### 1.4 Inconsistent `batch_id`/`batch_details_id` binding + missing `::bigint` casts in the final status update

**Where:** "DB Calls" destination, final per-file status computation (~line 3637–3733).

- `batch_id`/`batch_details_id` are now captured once at the top of the function with a `|| null` fallback (empty string `""` — not `null` — was previously bound when the value was unset, which fails a `?::bigint` cast):
  ```diff
  - var batch_id = $("batch_id");
  - var batch_details_id = $("batch_details_id");
  + //$('batch_id')/$('batch_details_id') resolve to "" (not null) when unset, which fails downstream ?::bigint casts
  + var batch_id = $("batch_id") || null;
  + var batch_details_id = $("batch_details_id") || null;
  ```
- All subsequent bind-array usages in that function were switched from repeated `$("batch_id")`/`$("batch_details_id")` channel-map reads to the local, null-safe variables.
- Added `::bigint` casts to `processed_count` and `errored_count` in the `UPDATE mco_audit.mco_records` / `mco_audit.mco_record_details` statements that set the file's final status (`FULLY_PROCESSED` / `PARTIALLY_PROCESSED` / `ERRORED`), and to `errored_count` in the resubmission-reconciliation statements further down — these previously bound as untyped `?`, which is more fragile against driver type inference.

---

## 2. Local test paths

All three channels point every **internal** file destination at local Windows folders instead of the real SFTP/network-share paths, so each channel can be started and exercised on a dev machine without live SFTP infrastructure. `Aetna.xml` uses one shared, non-MCO-specific folder set (`d:/mco`, `d:/mco-success`, `d:/mco-err`, …). For `Amida Care.xml` and `Anthem.xml`, **each channel was given its own folder set** (derived from its `mco_sftp_folder` value) instead of reusing Aetna's literal paths, so the three channels' local test data can't collide if more than one is ever started at once:

| Purpose | Aetna | Amida Care | Anthem |
|---|---|---|---|
| Source connector (inbound poll) | `d:/mco` | `d:/mco-amidacare` | `d:/mco-anthem` |
| Success Data destination | `d:/mco-success` | `d:/mco-amidacare-success` | `d:/mco-anthem-success` |
| Error Data destination | `d:/mco-err` | `d:/mco-amidacare-err` | `d:/mco-anthem-err` |
| Rejection receipt (outbound) | `d:/mco-rej` | `d:/mco-amidacare-rej` | `d:/mco-anthem-rej` |
| Success receipt (outbound) | `d:/mco-success-receipt` | `d:/mco-amidacare-success-receipt` | `d:/mco-anthem-success-receipt` |
| Archive — success receipt | `d:/mco-success-archive` | `d:/mco-amidacare-success-archive` | `d:/mco-anthem-success-archive` |
| Archive — rejection receipt | `d:/mco-rej-receipt` | `d:/mco-amidacare-rej-receipt` | `d:/mco-anthem-rej-receipt` |

Also updated to match (in Amida Care and Anthem, mirroring Aetna's structure):
- The two `fileNameToDelete` / `errorFileNameToDelete` cleanup helpers (used when a duplicate/re-header/mismatched-count condition needs to remove a partially-written Success/Error file) build paths from the local folders above instead of `/mco-internal/internal/${mco_sftp_folder}/prod/mirth_processed/...`.
- The helper that re-reads a previously written Success/Error file before pushing it out over SFTP (inside the "Stream file to SFTP" destination) resolves it from the local success/err folder based on the `Error_` filename prefix, instead of the internal share path.

**Not changed** (consistent across all three channels): the actual outbound SFTP push in destination 6 (`Stream file to SFTP`) — the real delivery to the MCO's outbound folder and to NYeC's data lake (`nyec-transfer.nyehealth.org`) — still uses the real `configurationMap`-driven SFTP connections. Only the *internal* intermediate storage paths were localized. The `thresholdExceeded` (dead-code, unreachable since threshold=100) and partial-success branches' references to the real outbound `.../esmf/outbound/error/` and `.../archive/.../mco_error/` folders, and the email alert text mentioning the SFTP outbound folder, are left pointing at the real paths in all three channels.

---

## 3. What was deliberately left alone

- Channel `name`, `mco_code`, `mco_sftp_folder`, and the `setMcoInfoInGlobalMap()` values — these are per-channel identity and must stay distinct across Aetna, Amida Care, and Anthem.
- `metadata`/`pruningSettings`/`lastModified` timestamps and `codeTemplateLibrary` revision/`enabledChannelIds` bookkeeping — server-side export state, not functional code. (`Aetna-original.xml` vs `Aetna.xml` shows these moving in *both* directions — e.g. the `MCO Code Template` library revision drops from 14 to 6 — simply because the two exports were taken at different points against a shared, multi-channel Mirth server; unrelated channels kept being edited in between.)
- A cosmetic `}*/` vs `//*/` difference in a commented-out validation block (EC096, first-name `"NULL"` check) — both forms close the JS block comment identically; there is no behavioral difference either way. (This particular diff only shows up in the Aetna-vs-Amida/Anthem comparison, not in Aetna-original-vs-Aetna, so it's unrelated to the testability fixes.)
- `Aetna.xml`'s channel `<id>` differs from `Aetna-original.xml`'s (`8624283c-3e5c-4ed0-84d0-d08ee6ca50da` → `1cd6979d-5109-42f4-b786-cdc9ada2236d`), consistent with the fixed version being re-imported/re-exported as part of applying and testing the fixes rather than edited in place. `Amida Care.xml` and `Anthem.xml` keep their own original ids — this is not something to replicate.

---

## 4. Verification performed

- `Aetna-original.xml` diffed directly against `Aetna.xml` (51 hunks) to obtain the ground-truth change set described above, superseding the earlier reconstruction that inferred it from the Aetna-vs-Amida/Anthem comparison alone. Every fix and path change listed in Sections 1–2 was confirmed to appear in that diff.
- `Amida Care.xml` and `Anthem.xml` both parse as well-formed XML after modification (`xml.dom.minidom`).
- All 15 bug-fix hunks (from `Aetna.xml`) applied cleanly against both files with `patch --fuzz=0` (no fuzzy/partial matches), confirming the pre-fix code in both channels was byte-identical to `Aetna-original.xml`'s pre-fix code at every changed location.
- Each local-path replacement in Amida Care and Anthem was applied with an exact expected occurrence count (1 for each single-use `<host>` element, 3 for each of the two `fileNameToDelete`/`errorFileNameToDelete` cleanup patterns) and every count matched before writing.
- Neither `Aetna.xml` nor `Aetna-original.xml` was modified in this pass — the latter is kept purely as a reference baseline.
