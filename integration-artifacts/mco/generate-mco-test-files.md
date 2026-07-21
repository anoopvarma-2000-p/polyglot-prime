# generate-mco-test-files.sh

Generates MDESMF test files for MCO channels by cloning the canonical AMI (Amida
Care) test-file set. Every MCO clone shares byte-identical validation logic and
reference data (see [MCO-Channels-Modifications.md](MCO-Channels-Modifications.md) /
[MCO-S3-Channels-Modifications.md](MCO-S3-Channels-Modifications.md)), so the only
thing that legitimately varies between channels is the filename prefix (`mco_code`)
and the file timestamps — the script exploits that to produce valid test data for
any MCO without hand-authoring per-MCO content.

## Modes

| Mode | Command | What it produces |
|---|---|---|
| Single MCO | `./generate-mco-test-files.sh <MCO_CODE> [output-dir]` | The **full template set** (all `AMI_MDESMF_*.txt` files, cloned) for one MCO code |
| All MCOs, one file each | `./generate-mco-test-files.sh -a\|--all [output-base-dir]` | **One** file (cloned from the last template) per known MCO code |
| All MCOs, full set, straight to S3 input | `./generate-mco-test-files.sh -u\|--upload [input-base-dir]` | The **full template set** for **every** known MCO code, written directly into each channel's S3-uploader input folder |

Only one mode flag may be given, as the first argument. With no flag, the script
runs in single-MCO mode and requires a code as the first argument.

### 1. Single MCO

```bash
./generate-mco-test-files.sh CDP
./generate-mco-test-files.sh eld /custom/output/dir     # code is case-insensitive
```

Clones every file in `test-files/AMI/AMI_MDESMF_*.txt` into `<output-dir>`
(default `test-files/<MCO_CODE>/`), renaming each to `<MCO_CODE>_MDESMF_<timestamp>.txt`.
Timestamps start at "now" and step forward by 15 minutes per file (matching the
spacing baked into the AMI template set itself).

### 2. All MCOs, one file each (`-a` / `--all`)

```bash
./generate-mco-test-files.sh -a
./generate-mco-test-files.sh --all /custom/output-base
```

For every code in `KNOWN_CODES`, clones just the *last* template file into
`<output-base-dir>/<CODE>/<CODE>_MDESMF_<timestamp>.txt` (default output base:
`test-files/`). Useful for a quick smoke test across all channels without
generating the full per-MCO scenario set.

### 3. All MCOs, full set, direct to S3 input folder (`-u` / `--upload`)

```bash
./generate-mco-test-files.sh -u
./generate-mco-test-files.sh --upload
./generate-mco-test-files.sh -u /mnt/d/mco-test    # explicit input-base-dir (also the default)
```

For every code in `KNOWN_CODES`, clones the **full** template set (all files,
same as single-MCO mode) into `<input-base-dir>/upload2s3-<CODE>/` — the exact
directory each deployed `sbx-S3-uploader-<CODE>.xml` channel polls (its
`<sourceConnector><properties><host>` is `D:/mco-test/upload2s3-<CODE>`; see
[s3-uploader-channels/sbx-S3-uploader-AET.xml](s3-uploader-channels/sbx-S3-uploader-AET.xml)
for the reference channel). Once files land there, the already-running channel
picks them up on its next poll and pushes them to the sandbox S3 bucket
(`txd-sbx-mco-sftp-inbound/bridgelink-inbound/<CODE>`) — no manual copy step
needed.

`input-base-dir` defaults to `/mnt/d/mco-test`, the WSL mount of the `D:/mco-test`
host root the channels are configured against. Pass an explicit path if running
somewhere that root isn't mounted at `/mnt/d`. The script errors out (exit 1) if
the resolved `input-base-dir` doesn't exist, rather than silently creating an
unrelated directory tree.

## Known MCO codes

```
AET ANT AMI CDP CEN ELD EMB EHP FDE FID HAM HEF IDP ICL MOL MPS MVP NAS RSG UTD VIC VNS WHB WPN
```

24 codes, matching the 24 `sbx-S3-uploader-<CODE>.xml` channels in
[s3-uploader-channels/](s3-uploader-channels/). A code outside this list is still
accepted (with a warning) as long as it's 2-4 letters, in case a new MCO is added
before this list is updated.

## Reporting-month caveat (EC092 / EC093)

The AMI template content's enrollment/disenrollment dates were generated to be
valid for a specific reporting month (July 2026 as of the current templates),
computed from the `startDate=20` / `endDate=4` window in
`config-map-export-example.properties`. `EC092`/`EC093` validate
`MBR_CONT_PLAN_ENROLL_DT` / `MBR_PROS_DISENROLL_DT` against that window.

If you run this script on/after the 20th or on/before the 4th of the month, it
prints a warning: the cloned dates may fall outside the *current* reporting
window and trigger `EC092`/`EC093` on files that are meant to be all-success.
Re-verify against `config-map-export-example.properties`, or regenerate the AMI
templates for the current reporting month, before trusting results generated
near that boundary.

## Output naming

All generated files follow `<CODE>_MDESMF_<YYYYMMDDHHMMSS>.txt`, matching the
`fileFilter`/`outputPattern` the channels expect (`*` in, `${originalFilename}`
out — see the source/destination connector config in
[s3-uploader-channels/sbx-S3-uploader-AET.xml](s3-uploader-channels/sbx-S3-uploader-AET.xml)).
Timestamps are generated from "now" plus a 15-minute step per file so files sort
and poll in the same relative order as the original AMI template set.

## Troubleshooting

- **`Error: template dir not found`** — run the script from anywhere; it locates
  `test-files/AMI/` relative to its own path (`SCRIPT_DIR`), not the caller's
  working directory. This error means that directory is missing/moved.
- **`Error: no template files found`** — `test-files/AMI/` exists but has no
  `AMI_MDESMF_*.txt` files in it.
- **`Error: input base dir not found` (`-u` mode)** — the resolved
  `input-base-dir` (default `/mnt/d/mco-test`) doesn't exist. Confirm the D:
  drive mount, or pass the correct base path explicitly.
- **`Warning: '<CODE>' is not in the known mco_audit.mco code list`** — the code
  isn't in `KNOWN_CODES` yet; the script proceeds anyway (single-MCO mode only —
  `-a`/`-u` always iterate the known list).
