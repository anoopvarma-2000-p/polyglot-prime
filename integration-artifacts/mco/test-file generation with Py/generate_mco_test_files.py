#!/usr/bin/env python3
"""
Generate MDESMF sample/test files for the MCO Mirth channels (Amida Care, VNS, etc.).

All date fields are computed relative to the current date using the same
reporting-month rolling-window algorithm the channel itself uses (startDate=20,
endDate=4 from configuration_map_export.properties), so generated files stay
valid indefinitely instead of drifting into EC092/EC093/EC094 failures as a
fixed template would.

Examples:
  # One success file for VNS
  python3 generate_mco_test_files.py create --mco VNS --type success

  # A mixed file (mostly clean, 4 bad rows) for Amida Care
  python3 generate_mco_test_files.py create --mco AMI --type mixed --rows 20 --error-rows 4

  # Success files for every MCO in one shot
  python3 generate_mco_test_files.py create --mco ALL --type success

  # An all-failing file targeting specific error codes
  python3 generate_mco_test_files.py create --mco FID --type failure --error-codes EC008,EC092,EC030

  # Resubmission of an existing file, with enroll/disenroll dates refreshed
  # against today's reporting month and otherwise unchanged
  python3 generate_mco_test_files.py resubmit --file VNS_MDESMF_20260810000002.txt --fix-dates

  # Resubmission that deliberately still has 2 bad rows (for reprocessing tests)
  python3 generate_mco_test_files.py resubmit --file VNS_MDESMF_20260810000002.txt --type mixed --error-rows 2

  python3 generate_mco_test_files.py list-mcos
"""

import argparse
import random
import re
import zlib
from datetime import date, timedelta
from pathlib import Path

# ---------------------------------------------------------------------------
# Channel constants (verified against VNS.xml / Amida Care.xml and
# integration-artifacts/mco/configuration-map/configuration_map_export.properties)
# ---------------------------------------------------------------------------

MCOS = {
    "AET": "Aetna",
    "AMI": "Amida Care",
    "ANT": "Anthem",
    "CDP": "CDPHP",
    "ELD": "Elder Plan",
    "EMB": "Emblem",
    "EHP": "Excellus",
    "FID": "Fidelis",
    "HAM": "Hamaspik",
    "HEF": "Healthfirst",
    "IDP": "Independent Health",
    "MVP": "MVP",
    "MPS": "Metroplus",
    "MOL": "Molina",
    "NAS": "Nascentia",
    "RSG": "Riverspring",
    "UTD": "United",
    "VNS": "VNS",
    "VIC": "Village Care",
    "WPN": "Wellpoint NYS",
    "WHB": "Wellpoint",
}

HEADER = [
    "MBR_ID", "MBR_LASTNAME", "MBR_FIRSTNAME", "MBR_DOB", "MBR_ADDR_LINE_1",
    "MBR_CITY", "MBR_POSTALCODE", "MBR_COUNTY", "PLAN_ID", "MBR_LOB",
    "MBR_LOB_DESC", "MBR_CONT_PLAN_ENROLL_DT", "MBR_PROS_DISENROLL_DT",
    "EPOP_HIGHUTILIZER", "EPOP_HHENROLLED", "EPOP_OTHER", "EPOP_PREG_POSTPARTUM",
    "EPOP_UNDER18NUTRITION", "EPOP_UNDER18", "EPOP_ADULTCJ", "CHRONIC_CONDITIONS",
    "UNCONTROLLED_ASTHMA", "NUTRITION_THERAPY", "HEATILLNESS_ERUC", "COLDILLNESS_ERUC",
    "THERMOREG_RISK", "HEALTHY_HOMES", "NUTRITION_COUNSEL", "MTM_ILOS",
    "OPWDD_WAIVER", "TBI_WAIVER", "NHTD_WAIVER", "CHILDRENS_WAIVER",
    "MBR_TELE_NUM_HOME", "MBR_TELE_NUM_CELL", "MBR_TELE_NUM_OTHER",
    "PCP_NPI_NAME", "PCP_NPI", "PCP_TELE_NUM", "EPOP_IDD",
]

BOOLEAN_COLUMNS = [
    "EPOP_HIGHUTILIZER", "EPOP_HHENROLLED", "EPOP_OTHER", "EPOP_PREG_POSTPARTUM",
    "EPOP_UNDER18NUTRITION", "EPOP_UNDER18", "EPOP_ADULTCJ", "CHRONIC_CONDITIONS",
    "UNCONTROLLED_ASTHMA", "NUTRITION_THERAPY", "HEATILLNESS_ERUC", "COLDILLNESS_ERUC",
    "THERMOREG_RISK", "HEALTHY_HOMES", "NUTRITION_COUNSEL", "MTM_ILOS",
    "OPWDD_WAIVER", "TBI_WAIVER", "NHTD_WAIVER", "CHILDRENS_WAIVER", "EPOP_IDD",
]

LOB_DESCRIPTIONS = ["Mainstream", "MLTCP", "MAP"]
COUNTY_CODES = [f"{n:02d}" for n in list(range(1, 63)) + [66]]  # from configuration_map_export.properties
PLAN_ID_EXCLUDED = {"00000", "99999"}

REPORTING_WINDOW_START_DAY = 20  # configurationMap "startDate"
REPORTING_WINDOW_END_DAY = 4     # configurationMap "endDate"

LAST_NAMES = ["Paperno", "Sizto", "Fernandes", "Berneche", "Kolek", "Brooksbank",
              "Fenner", "Fallows", "Gilstorf", "Whitcombe", "Delacroix", "Nakashima",
              "Ferretti", "Okafor", "Bellweather", "Alvarado", "Nguyen", "Petrosyan"]
FIRST_NAMES = ["Prent", "Jenny", "Isaac", "Kimberley", "Rorie", "Constantinos",
               "Blancha", "Kevina", "Leshia", "Marcus", "Odette", "Renji",
               "Sable", "Dashiell", "Wren", "Elena", "Trevor", "Ada"]
CITIES = [("Wolcott", "14590"), ("Sodus Point", "14555"), ("North Java", "14113"),
          ("Farmington", "14425"), ("Hornell", "14843"), ("Reading Center", "14876"),
          ("Lyons", "14489"), ("Watkins Glen", "14891"), ("Canandaigua", "14424"),
          ("Geneseo", "14454"), ("Batavia", "14020"), ("Buffalo", "14201"),
          ("Ithaca", "14850"), ("Elmira", "14901"), ("Rochester", "14604")]
STREETS = ["Poway Creek Rd", "N Blackstone", "Aldo Ct", "Peffley St",
           "N Harper Road Ext", "New Waverly Pl", "Northshore Dr",
           "Office Court Dr Ste 705", "New Hampshire Ave", "Lakeview Ter",
           "Cobblestone Way", "Ridge Road W", "Meridian Cir", "Highgate Ave",
           "Southpoint Landing Blvd Ste 211"]
PCP_NAMES = ["Jane Roch", "Ann Lee", "John Roe", "Jane Doe", "Priya Nair",
             "Sable Reyes", "John Smith", "Marcus Webb", "NULL"]

FILENAME_RE = re.compile(
    r"^(?:(RS)_)?([A-Z]{3})_MDESMF_(\d{14})(?:_(\d{14}))?\.txt$"
)


# ---------------------------------------------------------------------------
# Reporting-month math (mirrors calculateReportingMonth() in the channel JS)
# ---------------------------------------------------------------------------

def reporting_month(today: date, start_day: int = REPORTING_WINDOW_START_DAY,
                     end_day: int = REPORTING_WINDOW_END_DAY) -> tuple[int, int]:
    month, year = today.month, today.year
    if start_day >= end_day and today.day >= start_day:
        if month != 12:
            month += 1
        else:
            month = 1
            year += 1
    return month, year


def reporting_month_bounds(today: date) -> tuple[date, date]:
    month, year = reporting_month(today)
    start = date(year, month, 1)
    end = (date(year + 1, 1, 1) if month == 12 else date(year, month + 1, 1)) - timedelta(days=1)
    return start, end


def fmt(d: date) -> str:
    return d.strftime("%Y%m%d")


# ---------------------------------------------------------------------------
# Row generation
# ---------------------------------------------------------------------------

def make_member_id(mco_code: str, seq: int) -> str:
    prefix = (mco_code[:2] if len(mco_code) >= 2 else (mco_code + "X")).upper()
    return f"{prefix}{seq:05d}A"


def valid_row(mco_code: str, seq: int, today: date) -> dict:
    win_start, win_end = reporting_month_bounds(today)
    last, first = random.choice(LAST_NAMES), random.choice(FIRST_NAMES)
    city, postal = random.choice(CITIES)

    dob = today - timedelta(days=random.randint(0, 90 * 365))
    enroll = win_end - timedelta(days=random.randint(0, min(2000, (win_end - date(2015, 1, 1)).days)))
    disenroll = win_start + timedelta(days=random.randint(180, 1200))

    plan_id = random.choice([p for p in (f"{n:05d}" for n in range(10000, 99999, 137)) if p not in PLAN_ID_EXCLUDED])

    row = {
        "MBR_ID": make_member_id(mco_code, seq),
        "MBR_LASTNAME": last,
        "MBR_FIRSTNAME": first,
        "MBR_DOB": fmt(dob),
        "MBR_ADDR_LINE_1": f"{random.randint(1, 99999)} {random.choice(STREETS)}",
        "MBR_CITY": city,
        "MBR_POSTALCODE": postal,
        "MBR_COUNTY": random.choice(COUNTY_CODES),
        "PLAN_ID": plan_id,
        "MBR_LOB": "NULL",
        "MBR_LOB_DESC": random.choice(LOB_DESCRIPTIONS),
        "MBR_CONT_PLAN_ENROLL_DT": fmt(enroll),
        "MBR_PROS_DISENROLL_DT": fmt(disenroll),
        "MBR_TELE_NUM_HOME": f"585555{seq % 10000:04d}",
        "MBR_TELE_NUM_CELL": f"585556{seq % 10000:04d}",
        "MBR_TELE_NUM_OTHER": "",
        "PCP_NPI_NAME": random.choice(PCP_NAMES),
        "PCP_NPI": f"1234{seq:06d}",
        "PCP_TELE_NUM": f"585557{seq % 10000:04d}",
    }
    for col in BOOLEAN_COLUMNS:
        row[col] = random.choice(["Y", "N", "U"])
    return row


# ---------------------------------------------------------------------------
# Error injectors: each corrupts exactly one field on an otherwise-valid row
# to trip a specific EC code. Registry key = EC code.
# ---------------------------------------------------------------------------

def _break(row, field, value):
    row = dict(row)
    row[field] = value
    return row


ERROR_INJECTORS = {
    "EC007": lambda r: _break(r, "MBR_ID", ""),
    "EC008": lambda r: _break(r, "MBR_ID", "AB123"),
    "EC009": lambda r: _break(r, "MBR_ID", "AB12345#"),
    "EC010": lambda r: _break(r, "MBR_LASTNAME", ""),
    "EC011": lambda r: _break(r, "MBR_LASTNAME", "X" * 51),
    "EC012": lambda r: _break(r, "MBR_FIRSTNAME", ""),
    "EC013": lambda r: _break(r, "MBR_FIRSTNAME", "X" * 51),
    "EC014": lambda r: _break(r, "MBR_DOB", ""),
    "EC015": lambda r: _break(r, "MBR_DOB", "2026-01-01"),
    "EC016": lambda r: _break(r, "MBR_ADDR_LINE_1", ""),
    "EC017": lambda r: _break(r, "MBR_ADDR_LINE_1", "X" * 51),
    "EC018": lambda r: _break(r, "MBR_CITY", ""),
    "EC019": lambda r: _break(r, "MBR_CITY", "X" * 51),
    "EC020": lambda r: _break(r, "MBR_POSTALCODE", ""),
    "EC021": lambda r: _break(r, "MBR_POSTALCODE", "123"),
    "EC022": lambda r: _break(r, "MBR_POSTALCODE", "1234A"),
    "EC023": lambda r: _break(r, "MBR_COUNTY", ""),
    "EC024": lambda r: _break(r, "MBR_COUNTY", "123"),
    "EC025": lambda r: _break(r, "MBR_COUNTY", "AB"),
    "EC026": lambda r: _break(r, "PLAN_ID", ""),
    "EC027": lambda r: _break(r, "PLAN_ID", "123"),
    "EC028": lambda r: _break(r, "PLAN_ID", "12A45"),
    "EC029": lambda r: _break(r, "MBR_LOB", ""),
    "EC030": lambda r: _break(r, "MBR_LOB", "12345678"),
    "EC032": lambda r: _break(r, "MBR_LOB_DESC", ""),
    "EC033": lambda r: _break(r, "MBR_LOB_DESC", "HMO"),
    "EC034": lambda r: _break(r, "MBR_CONT_PLAN_ENROLL_DT", ""),
    "EC035": lambda r: _break(r, "MBR_CONT_PLAN_ENROLL_DT", "01/01/2026"),
    "EC036": lambda r: _break(r, "MBR_PROS_DISENROLL_DT", ""),
    "EC037": lambda r: _break(r, "MBR_PROS_DISENROLL_DT", "01/01/2026"),
    "EC038": lambda r: _break(r, "EPOP_HIGHUTILIZER", ""),
    "EC039": lambda r: _break(r, "EPOP_HIGHUTILIZER", "X"),
    "EC091": lambda r: _break(r, "PLAN_ID", "00000"),
    "EC094": lambda r: _break(r, "MBR_DOB", fmt(date.today() + timedelta(days=30))),
    "EC095": lambda r: _break(r, "MBR_COUNTY", "97"),
    "EC098": lambda r: _break(r, "MBR_ADDR_LINE_1", "NULL"),
    "EC099": lambda r: _break(r, "MBR_CITY", "NULL"),
    "EC100": lambda r: _break(r, "MBR_TELE_NUM_HOME", "12345"),
    "EC101": lambda r: _break(r, "MBR_TELE_NUM_HOME", "58555ABCDE"),
    "EC106": lambda r: _break(r, "EPOP_IDD", ""),
    "EC107": lambda r: _break(r, "EPOP_IDD", "X"),
    "EC108": lambda r: _break(r, "PCP_NPI_NAME", "X" * 51),
    "EC109": lambda r: _break(r, "PCP_NPI", "123"),
    "EC110": lambda r: _break(r, "PCP_NPI", "12345ABCDE"),
}


# EC093 (enroll date past reporting month end) / EC092 (disenroll date before reporting month start)
# computed against *today* rather than fixed offsets, same as the other date fields.
ERROR_INJECTORS["EC093"] = lambda r: _break(r, "MBR_CONT_PLAN_ENROLL_DT",
                                             fmt(reporting_month_bounds(date.today())[1] + timedelta(days=45)))
ERROR_INJECTORS["EC092"] = lambda r: _break(r, "MBR_PROS_DISENROLL_DT",
                                             fmt(reporting_month_bounds(date.today())[0] - timedelta(days=45)))

ERROR_CODES = sorted(ERROR_INJECTORS)


def error_row(mco_code: str, seq: int, today: date, forced_code: str = None) -> tuple[dict, str]:
    base = valid_row(mco_code, seq, today)
    code = forced_code or random.choice(ERROR_CODES)
    return ERROR_INJECTORS[code](base), code


# ---------------------------------------------------------------------------
# File assembly
# ---------------------------------------------------------------------------

def row_line(row: dict) -> str:
    return "|".join(row.get(col, "") for col in HEADER)


def build_rows(mco_code, num_rows, kind, error_rows, error_codes, seed, today, start_seq=1):
    if seed is not None:
        random.seed(seed)
    if kind == "failure":
        n_bad = num_rows
    elif kind == "mixed":
        n_bad = error_rows if error_rows is not None else max(1, num_rows // 5)
    else:
        n_bad = 0
    n_bad = min(n_bad, num_rows)

    bad_positions = set(random.sample(range(num_rows), n_bad)) if n_bad else set()
    codes_cycle = list(error_codes) if error_codes else None

    lines, applied_errors = [], []
    for i in range(num_rows):
        seq = start_seq + i
        if i in bad_positions:
            forced = codes_cycle[i % len(codes_cycle)] if codes_cycle else None
            row, code = error_row(mco_code, seq, today, forced_code=forced)
            applied_errors.append((seq, code))
        else:
            row = valid_row(mco_code, seq, today)
        lines.append(row_line(row))
    return lines, applied_errors


def build_content(lines) -> str:
    return "\n".join(["|".join(HEADER)] + lines + ["EOF"]) + "\n"


def original_filename(mco_code: str, ts: str) -> str:
    return f"{mco_code}_MDESMF_{ts}.txt"


def resubmission_filename(mco_code: str, orig_ts: str, new_ts: str) -> str:
    return f"RS_{mco_code}_MDESMF_{orig_ts}_{new_ts}.txt"


def unique_filename(outdir: Path, build_name) -> tuple[str, str]:
    """Find a 14-digit timestamp (second resolution, matching the real MDESMF convention)
    that doesn't collide with a file already in outdir -- guards against two files/resubmissions
    generated within the same wall-clock second silently overwriting each other."""
    from datetime import datetime, timedelta
    dt = datetime.now()
    while True:
        ts = dt.strftime("%Y%m%d%H%M%S")
        fname = build_name(ts)
        if not (outdir / fname).exists():
            return fname, ts
        dt += timedelta(seconds=1)


# ---------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------

def resolve_mco_codes(arg: str) -> list[str]:
    if arg.upper() == "ALL":
        return list(MCOS)
    codes = [c.strip().upper() for c in arg.split(",")]
    unknown = [c for c in codes if c not in MCOS]
    if unknown:
        raise SystemExit(f"Unknown MCO code(s): {', '.join(unknown)}. Use list-mcos to see valid codes.")
    return codes


def cmd_create(args):
    outdir = Path(args.outdir)
    outdir.mkdir(parents=True, exist_ok=True)
    today = date.today()
    codes = resolve_mco_codes(args.mco)
    error_codes = [c.strip().upper() for c in args.error_codes.split(",")] if args.error_codes else None

    created = []
    for code in codes:
        for n in range(args.count):
            fname, ts = unique_filename(outdir, lambda t, c=code: original_filename(c, t))
            # zlib.crc32, not hash(): Python's built-in hash() is randomized per-process
            # (PYTHONHASHSEED), so it would make --seed non-reproducible across separate runs.
            seed = (args.seed + zlib.crc32(f"{code}:{n}".encode())) if args.seed is not None else None
            lines, errors = build_rows(code, args.rows, args.type, args.error_rows,
                                        error_codes, seed, today)
            (outdir / fname).write_text(build_content(lines))
            created.append((fname, len(lines), len(errors)))

    print(f"Created {len(created)} file(s) in {outdir}:")
    for fname, total, bad in created:
        print(f"  {fname}  ({total} rows, {bad} error row(s))")


def cmd_resubmit(args):
    src = Path(args.file)
    if not src.exists():
        raise SystemExit(f"File not found: {src}")

    m = FILENAME_RE.match(src.name)
    if not m:
        raise SystemExit(f"'{src.name}' doesn't match <MCO>_MDESMF_<14digits>.txt "
                          f"or RS_<MCO>_MDESMF_<14digits>_<14digits>.txt")
    _, mco_code, orig_ts, _ = m.groups()
    if mco_code not in MCOS:
        raise SystemExit(f"Unknown MCO code in filename: {mco_code}")

    lines = [ln for ln in src.read_text().splitlines() if ln.strip()]
    if not lines:
        raise SystemExit(f"{src} is empty")
    if lines[-1].strip().upper() == "EOF":
        lines = lines[:-1]
    data_lines = lines[1:] if lines[0].startswith("MBR_ID|") else lines  # drop header if present

    today = date.today()
    if args.seed is not None:
        random.seed(args.seed)

    if args.fix_dates:
        win_start, win_end = reporting_month_bounds(today)
        fixed = []
        for ln in data_lines:
            fields = ln.split("|")
            if len(fields) != len(HEADER):
                fixed.append(ln)
                continue
            row = dict(zip(HEADER, fields))
            enroll = win_end - timedelta(days=random.randint(0, 1500))
            disenroll = win_start + timedelta(days=random.randint(180, 1200))
            row["MBR_CONT_PLAN_ENROLL_DT"] = fmt(enroll)
            row["MBR_PROS_DISENROLL_DT"] = fmt(disenroll)
            fixed.append(row_line(row))
        data_lines = fixed

    if args.type != "success" or args.error_rows:
        error_codes = [c.strip().upper() for c in args.error_codes.split(",")] if args.error_codes else None
        n_bad = args.error_rows if args.error_rows is not None else max(1, len(data_lines) // 5)
        n_bad = min(n_bad, len(data_lines))
        bad_positions = set(random.sample(range(len(data_lines)), n_bad)) if n_bad else set()
        codes_cycle = error_codes
        new_lines = []
        for i, ln in enumerate(data_lines):
            if i in bad_positions:
                fields = ln.split("|")
                row = dict(zip(HEADER, fields)) if len(fields) == len(HEADER) else valid_row(mco_code, i + 1, today)
                forced = codes_cycle[i % len(codes_cycle)] if codes_cycle else None
                code = forced or random.choice(ERROR_CODES)
                row = ERROR_INJECTORS[code](row)
                new_lines.append(row_line(row))
            else:
                new_lines.append(ln)
        data_lines = new_lines

    outdir = Path(args.outdir)
    outdir.mkdir(parents=True, exist_ok=True)
    fname, new_ts = unique_filename(outdir, lambda t: resubmission_filename(mco_code, orig_ts, t))
    (outdir / fname).write_text(build_content(data_lines))
    print(f"Created resubmission: {outdir / fname}")
    print(f"  root file : {original_filename(mco_code, orig_ts)}")
    print(f"  rows      : {len(data_lines)}")
    print(f"  fix-dates : {args.fix_dates}")


def cmd_list_mcos(_args):
    for code, name in sorted(MCOS.items()):
        print(f"{code}  {name}")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def build_parser():
    p = argparse.ArgumentParser(description="Generate MDESMF sample/test files for MCO Mirth channels.")
    sub = p.add_subparsers(dest="command", required=True)

    common_gen = argparse.ArgumentParser(add_help=False)
    common_gen.add_argument("--type", choices=["success", "failure", "mixed"], default="success",
                             help="Row content: all-clean, all-erroring, or a blend (default: success)")
    common_gen.add_argument("--error-rows", type=int, default=None,
                             help="Number of erroring rows for --type mixed (default: ~20%% of rows)")
    common_gen.add_argument("--error-codes", default=None,
                             help="Comma-separated EC codes to cycle through for error rows "
                                  "(default: random from the full validation matrix)")
    common_gen.add_argument("--outdir", default=str(Path(__file__).parent),
                             help="Output directory (default: this script's directory)")
    common_gen.add_argument("--seed", type=int, default=None, help="Random seed for reproducible output")

    pc = sub.add_parser("create", parents=[common_gen], help="Create original (non-resubmission) sample files")
    pc.add_argument("--mco", required=True,
                     help="MCO code (e.g. VNS), comma-separated list, or ALL")
    pc.add_argument("--rows", type=int, default=15, help="Member rows per file (default: 15)")
    pc.add_argument("--count", type=int, default=1, help="Files to create per MCO (default: 1)")
    pc.set_defaults(func=cmd_create)

    pr = sub.add_parser("resubmit", parents=[common_gen],
                         help="Create an RS_ resubmission file from an existing sample file")
    pr.add_argument("--file", required=True, help="Path to the original or prior RS_ sample file")
    pr.add_argument("--fix-dates", action="store_true",
                     help="Refresh MBR_CONT_PLAN_ENROLL_DT/MBR_PROS_DISENROLL_DT on every row so "
                          "they clear today's reporting-month window (EC092/EC093), leaving other fields untouched")
    pr.set_defaults(func=cmd_resubmit)

    pl = sub.add_parser("list-mcos", help="List known MCO codes")
    pl.set_defaults(func=cmd_list_mcos)

    return p


def main(argv=None):
    args = build_parser().parse_args(argv)
    args.func(args)


if __name__ == "__main__":
    main()
