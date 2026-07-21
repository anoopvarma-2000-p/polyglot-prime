#!/usr/bin/env bash
# Generate MDESMF test files for a given MCO channel by cloning the canonical
# AMI (Amida Care) test-file set — every MCO clone shares byte-identical
# validation logic and reference data (see MCO-Channels-Modifications.md /
# MCO-S3-Channels-Modifications.md), so the only thing that legitimately
# varies between channels is the filename prefix and, here, the timestamps.
#
# Usage: ./generate-mco-test-files.sh <MCO_CODE> [output-dir]
#        ./generate-mco-test-files.sh -a|--all [output-base-dir]
#        ./generate-mco-test-files.sh -u|--upload [input-base-dir]
#   MCO_CODE         3-4 letter mco_audit.mco.mco_code, e.g. CDP, ELD, WHB
#   output-dir       optional; defaults to test-files/<MCO_CODE> next to this script
#   -a, --all        generate one test file (cloned from the last template file)
#                     for every known MCO code, instead of the full template set
#                     for a single MCO
#   output-base-dir  optional with -a/--all; each MCO's single file is written to
#                     <output-base-dir>/<MCO_CODE>/; defaults to test-files/ next
#                     to this script
#   -u, --upload     generate the full template set (like the single-MCO case)
#                     for every known MCO code, writing straight into each MCO's
#                     S3-uploader channel input folder (see s3-uploader-channels/
#                     sbx-S3-uploader-<CODE>.xml <sourceConnector><host>) so the
#                     already-deployed/polling channel picks the files up and
#                     pushes them to S3 without a manual copy step
#   input-base-dir   optional with -u/--upload; files are written to
#                     <input-base-dir>/upload2s3-<CODE>/; defaults to
#                     /mnt/d/mco-test (the WSL mount of the D:/mco-test host
#                     path the channels poll)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_FILES_DIR="$SCRIPT_DIR/test-files"
TEMPLATE_DIR="$TEST_FILES_DIR/AMI"
TEMPLATE_PREFIX="AMI"
DEFAULT_INPUT_BASE_DIR="/mnt/d/mco-test"

KNOWN_CODES="AET ANT AMI CDP CEN ELD EMB EHP FDE FID HAM HEF IDP ICL MOL MPS MVP NAS RSG UTD VIC VNS WHB WPN"

usage() {
    echo "Usage: $0 <MCO_CODE> [output-dir]" >&2
    echo "       $0 -a|--all [output-base-dir]" >&2
    echo "       $0 -u|--upload [input-base-dir]" >&2
    echo "  MCO_CODE          3-4 letter mco_code (e.g. CDP, ELD, WHB)" >&2
    echo "  -a, --all         generate one test file per known MCO code" >&2
    echo "  -u, --upload      generate the full template set per known MCO code" >&2
    echo "                    directly into each MCO's S3-uploader input folder" >&2
    exit 1
}

ALL_MODE=false
UPLOAD_MODE=false
case "${1:-}" in
    -a|-all|--all)
        ALL_MODE=true
        shift
        ;;
    -u|-upload|--upload)
        UPLOAD_MODE=true
        shift
        ;;
esac

if $UPLOAD_MODE; then
    [ $# -le 1 ] || usage
    INPUT_BASE_DIR="${1:-$DEFAULT_INPUT_BASE_DIR}"
elif $ALL_MODE; then
    [ $# -le 1 ] || usage
    OUT_BASE_DIR="${1:-$TEST_FILES_DIR}"
else
    [ $# -ge 1 ] && [ $# -le 2 ] || usage

    MCO_CODE=$(echo "$1" | tr '[:lower:]' '[:upper:]')
    OUT_DIR="${2:-$TEST_FILES_DIR/$MCO_CODE}"

    if ! [[ "$MCO_CODE" =~ ^[A-Z]{2,4}$ ]]; then
        echo "Error: MCO code must be 2-4 letters, got '$1'" >&2
        exit 1
    fi

    case " $KNOWN_CODES " in
        *" $MCO_CODE "*) ;;
        *) echo "Warning: '$MCO_CODE' is not in the known mco_audit.mco code list; proceeding anyway." >&2 ;;
    esac
fi

if [ ! -d "$TEMPLATE_DIR" ]; then
    echo "Error: template dir not found: $TEMPLATE_DIR" >&2
    exit 1
fi

mapfile -t TEMPLATE_FILES < <(find "$TEMPLATE_DIR" -maxdepth 1 -name "${TEMPLATE_PREFIX}_MDESMF_*.txt" | sort)
if [ "${#TEMPLATE_FILES[@]}" -eq 0 ]; then
    echo "Error: no template files found in $TEMPLATE_DIR" >&2
    exit 1
fi

# Reporting-month sanity check: EC092/EC093 validate MBR_CONT_PLAN_ENROLL_DT /
# MBR_PROS_DISENROLL_DT against a reporting-month window derived from
# config-map-export-example.properties (startDate=20 / endDate=4). The
# template content's dates were generated for July 2026; they only stay valid
# while "today" resolves to the same reporting month as that template run.
DAY_OF_MONTH=$(date +%-d)
if [ "$DAY_OF_MONTH" -ge 20 ] || [ "$DAY_OF_MONTH" -le 4 ]; then
    echo "Warning: today is near the startDate=20/endDate=4 reporting-month boundary." >&2
    echo "         The cloned enroll/disenroll dates (tuned for the template's original" >&2
    echo "         reporting month) may fall outside the current window and trigger" >&2
    echo "         EC092/EC093 on files meant to be all-success. Verify against" >&2
    echo "         config-map-export-example.properties before trusting results." >&2
fi

BASE_EPOCH=$(date +%s)
INTERVAL=900 # 15 minutes between files, matching the template's own spacing
CREATED=()

if $UPLOAD_MODE; then
    if [ ! -d "$INPUT_BASE_DIR" ]; then
        echo "Error: input base dir not found: $INPUT_BASE_DIR" >&2
        echo "       (expected the mount/path of the D:/mco-test host root the" >&2
        echo "       S3-uploader channels poll; pass it explicitly if it differs)" >&2
        exit 1
    fi

    for CODE in $KNOWN_CODES; do
        code_in_dir="$INPUT_BASE_DIR/upload2s3-$CODE"
        mkdir -p "$code_in_dir"

        i=0
        for template_file in "${TEMPLATE_FILES[@]}"; do
            ts=$(date -d "@$((BASE_EPOCH + i * INTERVAL))" +%Y%m%d%H%M%S)
            out_file="$code_in_dir/${CODE}_MDESMF_${ts}.txt"
            cp "$template_file" "$out_file"
            CREATED+=("$out_file")
            i=$((i + 1))
        done
    done

    echo "Generated ${#CREATED[@]} test file(s) across $(wc -w <<< "$KNOWN_CODES") MCO codes, copied into"
    echo "each channel's input folder under $INPUT_BASE_DIR:"
    for f in "${CREATED[@]}"; do
        echo "  ${f#"$INPUT_BASE_DIR"/}"
    done
elif $ALL_MODE; then
    LAST_TEMPLATE="${TEMPLATE_FILES[-1]}"
    mkdir -p "$OUT_BASE_DIR"

    i=0
    for CODE in $KNOWN_CODES; do
        ts=$(date -d "@$((BASE_EPOCH + i * INTERVAL))" +%Y%m%d%H%M%S)
        code_out_dir="$OUT_BASE_DIR/$CODE"
        mkdir -p "$code_out_dir"
        out_file="$code_out_dir/${CODE}_MDESMF_${ts}.txt"
        cp "$LAST_TEMPLATE" "$out_file"
        CREATED+=("$out_file")
        i=$((i + 1))
    done

    echo "Generated ${#CREATED[@]} test file(s), one per MCO code, in $OUT_BASE_DIR:"
    for f in "${CREATED[@]}"; do
        echo "  ${f#"$OUT_BASE_DIR"/}"
    done
else
    mkdir -p "$OUT_DIR"

    i=0
    for template_file in "${TEMPLATE_FILES[@]}"; do
        ts=$(date -d "@$((BASE_EPOCH + i * INTERVAL))" +%Y%m%d%H%M%S)
        out_file="$OUT_DIR/${MCO_CODE}_MDESMF_${ts}.txt"
        cp "$template_file" "$out_file"
        CREATED+=("$out_file")
        i=$((i + 1))
    done

    echo "Generated ${#CREATED[@]} test files for MCO code '$MCO_CODE' in $OUT_DIR:"
    for f in "${CREATED[@]}"; do
        echo "  $(basename "$f")"
    done
fi
