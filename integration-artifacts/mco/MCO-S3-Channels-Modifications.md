# S3-Compatible Channel Generation — 18 New MCO Channels

**Files produced:** `CDPHP S3.xml`, `Elder Plan S3.xml`, `Emblem S3.xml`, `Excellus S3.xml`, `Fidelis S3.xml`, `Hamaspik S3.xml`, `Healthfirst S3.xml`, `Independent Health S3.xml`, `MVP S3.xml`, `Metroplus S3.xml`, `Molina S3.xml`, `Nascentia S3.xml`, `Riverspring S3.xml`, `United S3.xml`, `VNS S3.xml`, `Village Care S3.xml`, `Wellpoint NYS S3.xml`, `Wellpoint S3.xml`
**Source files (untouched):** the 18 corresponding `<Name>.xml` files added to this branch
**Reference / ground truth:** [`Aetna S3.xml`](Aetna%20S3.xml) vs [`Aetna.xml`](Aetna.xml) — the confirmed-working S3 conversion, per [MCO-Channels-Modifications.md](MCO-Channels-Modifications.md). Every edit below reproduces that same diff pattern against each of the 18 new source files.
**Baseline:** the 18 new channels are per-MCO clones of the same channel body (only `mco_code` / `mco_sftp_folder` / channel identity differ), structurally identical to each other — confirmed by identical hit-counts for every pattern touched (7× `<scheme>FILE</scheme>`, 4× `fileNameToDelete` cleanup sites, 7× `<anonymous>true</anonymous>`, 1× `FileInputStream` — all 18 files scored the same before conversion).
**Method:** a Python script applied the same 13 literal find/replace edits to each file (each edit asserted to match **exactly once** per file before writing, and every output re-parsed with `xml.dom.minidom` to confirm well-formed XML). No manual/per-file freehand edits were made — this guarantees the 18 outputs differ from each other only in the identity fields (`mco_code`, folder names, channel id/name), exactly like the Aetna/Amida Care/Anthem set.

---

## 1. What "S3 compatible" means here

Unlike Aetna/Amida Care/Anthem (which started from a **local-disk test path** baseline — `d:/mco...`), these 18 channels were added directly from **production** exports, so their internal file destinations use the real share paths (`/mco-internal/internal/${mco_sftp_folder}/...`, `${root_sftp_folder}/${mco_sftp_folder}/esmf/outbound/...`, `${root_sftp_folder}/archive/${mco_sftp_folder}/...`) built from Mirth `configurationMap`/`globalChannelMap` variables rather than literal folders. The S3 conversion targets the same **7 FILE connectors per channel** as Aetna S3 did, replacing them with `S3` scheme connectors pointed at the shared sandbox bucket `txd-sbx-mco-sftp-inbound`, under prefix `bridgelink-inbound/<mco_code>[-suffix]`:

| Connector | Old scheme/host (production) | New S3 host |
|---|---|---|
| Source (inbound poll) | `FILE` `/lza-prod-esmf/internal/<mco_sftp_folder>/prod/mirth_inbound` | `bridgelink-inbound/<CODE>` |
| Success Data (destination 2) | `FILE` `/mco-internal/internal/${mco_sftp_folder}/prod/mirth_processed` (`Success_${originalFilename}`) | `bridgelink-inbound/<CODE>-success` |
| Error Data (destination 3) | `FILE` `/mco-internal/internal/${mco_sftp_folder}/prod/mirth_processed` (`Error_${originalFilename}`) | `bridgelink-inbound/<CODE>-error` |
| Send Rejection Receipt File | `FILE` `${root_sftp_folder}/${mco_sftp_folder}/esmf/outbound/error` | `bridgelink-inbound/<CODE>-rejected` |
| Send Success Receipt | `FILE` `${root_sftp_folder}/${mco_sftp_folder}/esmf/outbound/success` | `bridgelink-inbound/<CODE>-success-receipt` |
| Archive Success Receipt | `FILE` `${root_sftp_folder}/archive/${mco_sftp_folder}/prod/mco_success` | `bridgelink-inbound/<CODE>-success-receipt-arch` |
| Archive Rejection Receipt | `FILE` `${root_sftp_folder}/archive/${mco_sftp_folder}/prod/mco_error` | `bridgelink-inbound/<CODE>-rej-receipt-arch` |

**Not changed** (consistent with Aetna S3): the disabled backup `Destination 1` (direct key-based SFTP to `nyec-transfer.nyehealth.org`) and the real outbound SFTP push inside the "Stream file to SFTP" destination's `sftpFileTransfer(..., isSftp=true, ...)` branch — both still use the real `configurationMap`-driven SFTP connections, since the data lake / MCO outbound delivery itself is out of scope; only the *internal* staging destinations move to S3.

---

## 2. Edits applied to every one of the 18 files

### 2.1 Channel identity
- `<id>` — replaced with a freshly generated UUID (so the S3 channel can be deployed alongside the original as a distinct channel). See the per-channel table in §3.
- `<name>` — suffixed with ` S3` (e.g. `CDPHP` → `CDPHP S3`).
- `<initialState>` — flipped from `STOPPED` to `STARTED`, matching `Aetna S3.xml`'s deploy-ready state.

### 2.2 Source connector (inbound poll)
`<scheme>FILE</scheme>` → `<scheme>S3</scheme>`, with an `S3SchemeProperties` block added (`useDefaultCredentialProviderChain=true`, `useTemporaryCredentials=false`, `duration=7200`, `region=us-east-1`), host rewritten to `txd-sbx-mco-sftp-inbound/bridgelink-inbound/<CODE>`, and credentials switched from `anonymous`/`anonymous`/`anonymous` to `anonymous=false` with `${s3AccessKeyId}` / `${s3SecretAccessKey}` (configurationMap-backed).

### 2.3 Success Data / Error Data destinations
Same scheme/host/credential swap as above, plus `<outputAppend>` flipped from `true` to `false` (S3 PUT overwrites; there is no append semantics to preserve, same reasoning as the Aetna S3 conversion).

### 2.4 Rejection Receipt / Success Receipt / Archive Success / Archive Rejection destinations
Same scheme/host/credential swap; `outputAppend` was already `false` in the production baseline for these four, so no change needed there.

### 2.5 New `deleteStagedS3Files(filename)` helper
Inserted into the source transformer script, immediately after `setMcoInfoInGlobalMap()`'s definition and before its invocation. Uses the AWS SDK (`AmazonS3ClientBuilder`) to delete any `Success_`/`Error_` object left in the `<CODE>-success` / `<CODE>-error` prefixes from a prior run of the same filename, wrapped in try/catch so a missing object doesn't fail reprocessing.

### 2.6 Three duplicate/reprocess cleanup call-sites
The original per-channel logic (rebuilding `fileNameToDelete`/`errorFileNameToDelete` against the internal `/mco-internal/internal/${mco_sftp_folder}/prod/mirth_processed/` share and calling `org.apache.commons.io.FileUtils.deleteQuietly(...)` twice) is replaced at all 3 call-sites with a single `deleteStagedS3Files(filename);` call, since that internal share is no longer where Success/Error content lives.

### 2.7 "Stream file to SFTP" destination — in-memory content instead of a local re-read
Previously this destination reconstructed the internal file path from `$('mco_sftp_folder')` and opened it with `FileInputStream`. Since Success Data/Error Data now write straight to S3 (not local/internal disk), the destination instead pulls the already-staged content out of the channel map (`$('validContent')` / `$('errorContent')`, chosen by the `Error_` filename prefix) and streams it via `ByteArrayInputStream`. The internal-share copy/move step (`FileUtils.copyFile`/`moveFile`) is replaced with `FileUtils.writeStringToFile(...)` writing that same in-memory content to the destination path. The real outbound SFTP `put` (the `isSftp === true` branch, further down in the same function) is untouched.

---

## 3. Per-channel identity reference

| Channel | `mco_code` | S3 bucket prefix | Channel id (old → new) |
|---|---|---|---|
| CDPHP | `CDP` | `bridgelink-inbound/CDP*` | `6c1b19ba-b834-42b5-9f39-c91953e0c46a` → `e58f5bd0-fee5-4ecb-868f-9a4a7c0ef689` |
| Elder Plan | `ELD` | `bridgelink-inbound/ELD*` | `14cc482e-1fa9-4f8f-b5fd-26143f03a2ca` → `04a01a97-c021-4f3e-b10d-426838e88ae3` |
| Emblem | `EMB` | `bridgelink-inbound/EMB*` | `80b85794-08de-498c-9795-33728b848779` → `a68d74fd-c26b-4cd2-885b-b2ad890b8494` |
| Excellus | `EHP` | `bridgelink-inbound/EHP*` | `b61709bc-100b-4b62-a392-3bc3c0f9ba39` → `e216b33f-b7f0-4bac-ba23-7750fa5b2af0` |
| Fidelis | `FID` | `bridgelink-inbound/FID*` | `cc1c69df-d964-46aa-97dc-1316bc27cb1a` → `b028255d-3d33-4562-b1a4-6b81c080013e` |
| Hamaspik | `HAM` | `bridgelink-inbound/HAM*` | `16b81600-110a-4f8b-b928-1e2283f52f7b` → `c1ded0ae-82b0-4f5d-b6ed-85a43e716cb5` |
| Healthfirst | `HEF` | `bridgelink-inbound/HEF*` | `a05a9d71-cc83-4d65-b879-3879e41b786c` → `1d692067-1995-41a8-8712-969387b1c04c` |
| Independent Health | `IDP` | `bridgelink-inbound/IDP*` | `9d04a3e5-3de0-4ec9-8c3c-172396aadd66` → `004d8d3d-cedc-45f8-955f-27e1606959c8` |
| MVP | `MVP` | `bridgelink-inbound/MVP*` | `714af5ee-4786-490b-8181-1fa12e7350d4` → `b24d4603-8f5a-4872-89d2-9b474d7ea6db` |
| Metroplus | `MPS` | `bridgelink-inbound/MPS*` | `bf80e46a-a3a9-4aba-981f-33343a6ef6ef` → `aa14ae37-7b8e-48c0-8cf0-c0fe150ff9a1` |
| Molina | `MOL` | `bridgelink-inbound/MOL*` | `1fe21ad2-b536-4b38-a222-6290756a945d` → `aa52ca3d-503a-432e-a446-db5ba9a9aec6` |
| Nascentia | `NAS` | `bridgelink-inbound/NAS*` | `7a6f221b-015c-49d3-a77b-d182b5d9d7bd` → `7940022b-7a39-470b-9936-bcf8b2f4982e` |
| Riverspring | `RSG` | `bridgelink-inbound/RSG*` | `92381ffd-96aa-4be1-a549-a61ff3515237` → `0de4f42b-5d9e-4802-b3ab-f8935d7c0d8a` |
| United | `UTD` | `bridgelink-inbound/UTD*` | `e452063d-675e-4c5f-b7c4-e14f8ad6eada` → `1771e2fd-3b46-4e64-bbea-0fa03e85671f` |
| VNS | `VNS` | `bridgelink-inbound/VNS*` | `a2957b84-d21c-4939-bf38-6a4fe3e3d581` → `48679919-904a-4913-a593-28f19da61615` |
| Village Care | `VIC` | `bridgelink-inbound/VIC*` | `48b99efd-7a7d-4ec3-b145-d3bb0a990992` → `3d78e317-ccf7-45e2-8cbe-200cbfc5ba21` |
| Wellpoint NYS | `WPN` | `bridgelink-inbound/WPN*` | `402de7fe-1fac-42e0-b7c9-5aff267241c6` → `2440a65b-486b-4f03-8972-8bace78287d3` |
| Wellpoint | `WHB` | `bridgelink-inbound/WHB*` | `dd37870d-8fb0-4d24-80d3-abf38b5d8c80` → `a542e427-909f-4963-a374-3a214dcb8bd1` |

`*` expands to the 7 prefixes from §1: bare `<CODE>` (inbound), `-success`, `-error`, `-rejected`, `-success-receipt`, `-success-receipt-arch`, `-rej-receipt-arch`.

---

## 4. What was deliberately left alone

- `mco_code`, `mco_sftp_folder`, `root_sftp_folder`, and all `globalChannelMap`/`configurationMap` per-channel identity values inside `setMcoInfoInGlobalMap()` — unchanged; only referenced (not duplicated) when building the new S3 paths and the `deleteStagedS3Files` bucket keys.
- The `checkContactTypes` function in the shared **MCO Code Template** code template library (a separate, globally-shared library appended after each channel's own body) still deletes from `.../prod/mirth_inbound` via `rootFolder`/`mcoFolder` variables — this matches `Aetna S3.xml`, which also left this shared/global function untouched.
- The disabled `Destination 1` (direct key-based SFTP, `SFTP` scheme) and the real outbound SFTP `put` inside "Stream file to SFTP" (`isSftp === true` branch) — both still point at the real `nyec-transfer.nyehealth.org` / MCO outbound infrastructure via `configurationMap`, unchanged from the source files.
- `revision`, `nextMetaDataId`, `metadata`/`pruningSettings`/`lastModified` timestamps, and `codeTemplateLibrary` revision/`enabledChannelIds` bookkeeping — export/server-side state, not functional code; not touched, same rationale as [MCO-Channels-Modifications.md §3](MCO-Channels-Modifications.md).

---

## 5. Verification performed

- Every one of the 13 find/replace edits was asserted to match **exactly once** in each of the 18 source files before being applied (a mismatch would have raised an error and aborted that file) — confirming all 18 channels really are byte-identical clones apart from identity fields, same as the existing Aetna/Amida Care/Anthem set.
- All 18 output files (`<Name> S3.xml`) parse as well-formed XML (`xml.dom.minidom.parse`).
- Post-conversion pattern counts checked per file: `<scheme>S3</scheme>` = 7, `<scheme>FILE</scheme>` = 0, `<anonymous>true</anonymous>` = 0, `deleteStagedS3Files` helper definitions = 1, call-sites = 3 — identical across all 18 files.
- A full diff of `CDPHP S3.xml` against `CDPHP.xml` was manually reviewed line-by-line and confirmed to contain only the edits described in §2 (plus the `initialState` flip) — no unrelated/accidental changes.
- The 18 original source files (`CDPHP.xml`, `Elder Plan.xml`, etc.) were **not modified** — each `<Name> S3.xml` is a new file, mirroring how `Aetna S3.xml` sits alongside `Aetna.xml`.
