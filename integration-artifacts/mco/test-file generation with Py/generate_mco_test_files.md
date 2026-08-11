# MCO Test File Generator

`generate_mco_test_files.py` creates MDESMF sample files for the MCO Mirth channels
(Amida Care, VNS, Fidelis, etc.) — original submissions and `RS_` resubmissions —
for local testing without hand-editing a template.

Python 3.9+, standard library only. No install step.

```bash
cd integration-artifacts/mco/mco-channel-files/nexus-sandbox
python3 generate_mco_test_files.py --help
```

## Why not just reuse a template file?

Every date field (`MBR_DOB`, `MBR_CONT_PLAN_ENROLL_DT`, `MBR_PROS_DISENROLL_DT`) is
computed **relative to today**, using the same reporting-month rolling-window rule
the channel itself runs (`startDate=20` / `endDate=4`, from
[`configuration-map/configuration_map_export.properties`](../../configuration-map/configuration_map_export.properties)):

- If today's day-of-month is **≥ 20**, the reporting month rolls to *next* month.
- Otherwise the reporting month is the *current* month.

A static template file eventually crosses that rolling window and starts throwing
`EC092` (disenroll date before the reporting month) / `EC093` (enroll date past the
reporting month). This script recomputes valid ranges every run, so generated files
stay valid indefinitely.

The full field-validation matrix (county codes, plan-ID exclusions, `Y`/`N`/`U`
flags, phone/PCP formats) is likewise sourced from the real `errorCodes` /
`countyCode` values in the same properties file, not guesswork.

---

## Commands

### `create` — new (non-resubmission) sample files

```
python3 generate_mco_test_files.py create --mco <CODE|CODE,CODE|ALL> [options]
```

| Flag | Default | Meaning |
|---|---|---|
| `--mco` | *(required)* | One MCO code, a comma-separated list, or `ALL` (all 21) |
| `--type` | `success` | `success` \| `failure` \| `mixed` |
| `--rows` | `15` | Member rows per file |
| `--error-rows` | ~20% of rows | Rows to corrupt, for `--type mixed` (ignored for `success`/`failure`) |
| `--error-codes` | random | Comma-separated `EC` codes to cycle through for error rows, e.g. `EC008,EC092` |
| `--count` | `1` | Files to generate per MCO |
| `--outdir` | script's own directory | Output directory |
| `--seed` | none (fully random) | Random seed — same seed + same options = byte-identical row data every run (see [Reproducibility with `--seed`](#reproducibility-with---seed)) |

`--type failure` corrupts **every** row (each with one violated rule, cycling
through `--error-codes` if given, otherwise a random EC code per row).
`--type mixed` corrupts only `--error-rows` of them and leaves the rest clean.

### `resubmit` — build an `RS_` file from an existing sample file

```
python3 generate_mco_test_files.py resubmit --file <path> [options]
```

Same `--type` / `--error-rows` / `--error-codes` / `--outdir` / `--seed` options as
`create`, plus:

| Flag | Meaning |
|---|---|
| `--file` | *(required)* Path to an existing `<CODE>_MDESMF_<14digits>.txt` or `RS_<CODE>_MDESMF_..._<14digits>.txt` |
| `--fix-dates` | Refresh `MBR_CONT_PLAN_ENROLL_DT` / `MBR_PROS_DISENROLL_DT` on **every** row against today's reporting window, leaving every other field untouched |

MCO code and the original 14-digit timestamp are parsed from `--file`'s name; the
new resubmission timestamp is generated automatically (collision-checked — two
files/resubmissions in the same wall-clock second get distinct timestamps, never
overwrite each other). Without `--type`/`--error-rows`, the resubmission's row data
is carried over unchanged (whatever was in the source file, errors included).

### `list-mcos`

```
python3 generate_mco_test_files.py list-mcos
```

Prints all 21 known codes and channel names.

---

## Reproducibility with `--seed`

Without `--seed`, every field the script picks (last name, county, plan ID, all
the `Y`/`N`/`U` flags, and the specific date within its valid window) comes from
Python's `random` module — the same command produces **different** row data on
every run. That's fine for one-off test files, but not if you want to hand
someone a reproducible case or diff two runs to isolate the effect of one changed
option.

`--seed N` fixes the randomness to a starting point, so the *same* command with
the *same* `--seed` produces **identical row data** every time:

```bash
# Run this twice — the member data (names, dates, flags, everything) is identical both times
python3 generate_mco_test_files.py create --mco VNS --rows 5 --seed 42

# A different seed (or omitting --seed) gives different-but-still-valid data
python3 generate_mco_test_files.py create --mco VNS --rows 5 --seed 99
python3 generate_mco_test_files.py create --mco VNS --rows 5              # unseeded
```

**The filename timestamp still changes on every run** (it's wall-clock time, by
design, so repeat runs never collide/overwrite) — only the *row content* inside
the file becomes reproducible. To actually compare two seeded runs, diff the file
contents rather than the filenames:

```bash
python3 generate_mco_test_files.py create --mco VNS --rows 5 --seed 42 --outdir /tmp/run1
python3 generate_mco_test_files.py create --mco VNS --rows 5 --seed 42 --outdir /tmp/run2
diff /tmp/run1/*.txt /tmp/run2/*.txt   # no output = identical
```

`--seed` also applies to `resubmit` (covers `--fix-dates`'s new date picks and any
`--type mixed`/`--error-rows` corruption), and to `create --mco ALL`/`--count N`
runs — each MCO/file in the batch still gets its own distinct data, but that
per-file variation is itself derived deterministically from the seed, so the whole
batch is reproducible together.

---

## Sample commands

```bash
# One success file for VNS, 15 rows (defaults)
python3 generate_mco_test_files.py create --mco VNS

# Success files for every MCO in one shot
python3 generate_mco_test_files.py create --mco ALL --type success

# A few MCOs at once, 25 rows each
python3 generate_mco_test_files.py create --mco AMI,VNS,FID --rows 25

# Mixed file: 20 rows, 4 deliberately bad (random EC codes)
python3 generate_mco_test_files.py create --mco AMI --type mixed --rows 20 --error-rows 4

# All rows failing, targeting specific error codes (cycled across rows)
python3 generate_mco_test_files.py create --mco FID --type failure --rows 6 \
  --error-codes EC008,EC092,EC030

# 3 success files for VNS in one run, reproducible via --seed
python3 generate_mco_test_files.py create --mco VNS --count 3 --seed 42

# Same seed, run again later -> identical row data (only the filename timestamp differs)
python3 generate_mco_test_files.py create --mco VNS --rows 5 --seed 42

# Seeded mixed file -> the same 2 rows get corrupted with the same EC codes every run
python3 generate_mco_test_files.py create --mco AMI --type mixed --rows 10 --error-rows 2 --seed 7

# Seeded resubmission with --fix-dates -> same replacement dates every run
python3 generate_mco_test_files.py resubmit --file VNS_MDESMF_20260810000002.txt --fix-dates --seed 7

# Write into a specific directory instead of the script's own folder
python3 generate_mco_test_files.py create --mco ALL --type mixed --error-rows 2 \
  --outdir /tmp/mco-samples

# Resubmission of an existing file, dates refreshed, content otherwise unchanged
python3 generate_mco_test_files.py resubmit --file VNS_MDESMF_20260810000002.txt --fix-dates

# Resubmission that still has 2 bad rows on purpose (test partial reprocessing)
python3 generate_mco_test_files.py resubmit --file VNS_MDESMF_20260810000002.txt \
  --type mixed --error-rows 2

# Resubmission-of-a-resubmission (chain another RS_ off a prior RS_ file)
python3 generate_mco_test_files.py resubmit \
  --file RS_VNS_MDESMF_20260810000002_20260810103000.txt --fix-dates

# List all supported MCO codes
python3 generate_mco_test_files.py list-mcos
```

## Filename conventions produced

| Kind | Pattern | Example |
|---|---|---|
| Original | `<CODE>_MDESMF_<14-digit timestamp>.txt` | `VNS_MDESMF_20260810170643.txt` |
| Resubmission | `RS_<CODE>_MDESMF_<original timestamp>_<new timestamp>.txt` | `RS_VNS_MDESMF_20260810000002_20260810170644.txt` |

Both match the exact filename-convention checks (`EC001`/`EC002`) enforced by the
channels — see [`mco-data-flow.md`](mco-data-flow.md) for the full validation walkthrough.

## Known MCO codes

| Code | Name | Code | Name |
|---|---|---|---|
| AET | Aetna | MVP | MVP |
| AMI | Amida Care | MPS | Metroplus |
| ANT | Anthem | MOL | Molina |
| CDP | CDPHP | NAS | Nascentia |
| ELD | Elder Plan | RSG | Riverspring |
| EMB | Emblem | UTD | United |
| EHP | Excellus | VNS | VNS |
| FID | Fidelis | VIC | Village Care |
| HAM | Hamaspik | WPN | Wellpoint NYS |
| HEF | Healthfirst | WHB | Wellpoint |
| IDP | Independent Health | | |
