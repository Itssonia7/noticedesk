#!/usr/bin/env bash
# check_sensitive_data.sh: shared privacy check used by the committed pre-commit hook
# (.githooks/pre-commit) and by CI (.github/workflows/sensitive-data-check.yml).
#
# It scans ONLY ADDED lines and rejects:
#   * GSTINs  [0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]  not listed in .githooks/allowed_ids.txt
#   * PANs    [A-Z]{5}[0-9]{4}[A-Z]  not listed (a PAN inside an allow-listed GSTIN is allowed)
#   * newly added / renamed / copied .pdf .docx .xlsx files not listed in .githooks/allowed_binaries.txt
#
# Findings print file, line number and a MASKED value (e.g. 27IIIJJ****I1Z9). The matching line
# itself is never printed.
#
# Usage:
#   check_sensitive_data.sh --staged              # pre-commit: the staged changes
#   check_sensitive_data.sh --commits <A>..<B>    # CI / audit: every non-merge commit in the range
#   check_sensitive_data.sh --commit <sha>        # one commit
# Exit code: 0 clean, 1 findings, 2 usage / git error.

set -uo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || { echo "check_sensitive_data: not inside a git repository" >&2; exit 2; }
ALLOWED_IDS_FILE="${ALLOWED_IDS_FILE:-$ROOT/.githooks/allowed_ids.txt}"
ALLOWED_BIN_FILE="${ALLOWED_BIN_FILE:-$ROOT/.githooks/allowed_binaries.txt}"

GIT=(git -C "$ROOT" -c core.quotePath=false)

# Allow-list content without comments / blank lines, one entry per line.
read_list() {
    [ -f "$1" ] || return 0
    sed -e 's/#.*$//' -e 's/[[:space:]]*$//' -e 's/^[[:space:]]*//' "$1" | grep -v '^$' || true
}

ALLOWED_IDS="$(read_list "$ALLOWED_IDS_FILE")"
ALLOWED_BINS="$(read_list "$ALLOWED_BIN_FILE")"

# Scan a unified diff (stdin) for IDs on added lines. $1 = label printed with each finding.
scan_diff_for_ids() {
    CSD_ALLOWED="$ALLOWED_IDS" awk -v label="$1" '
    BEGIN {
        n = split(ENVIRON["CSD_ALLOWED"], a, "\n")
        for (i = 1; i <= n; i++) if (a[i] != "") ok[a[i]] = 1
        # PANs embedded in allow-listed GSTINs are allowed too
        for (id in ok) if (length(id) == 15) okpan[substr(id, 3, 10)] = 1
        GSTIN = "[0-9][0-9][A-Z][A-Z][A-Z][A-Z][A-Z][0-9][0-9][0-9][0-9][A-Z][1-9A-Z]Z[0-9A-Z]"
        PAN   = "[A-Z][A-Z][A-Z][A-Z][A-Z][0-9][0-9][0-9][0-9][A-Z]"
        found = 0
    }
    function report(kind, value, masked) {
        printf("%s%s:%d: possible %s %s\n", label, file, lineno, kind, masked)
        found = 1
    }
    /^\+\+\+ / {
        file = substr($0, 5); sub(/^b\//, "", file); next
    }
    /^--- / { next }
    /^@@ / {
        # @@ -a,b +c,d @@  -> next added line is number c
        h = $0; sub(/^@@ -[0-9,]+ \+/, "", h); sub(/[, ].*$/, "", h)
        lineno = h + 0; next
    }
    /^\+/ {
        text = substr($0, 2)
        # 1) GSTINs first; blank every GSTIN out so its embedded PAN is not reported again
        rest = text
        while (match(rest, GSTIN)) {
            v = substr(rest, RSTART, RLENGTH)
            if (!(v in ok)) report("GSTIN", v, substr(v, 1, 7) "****" substr(v, 12, 4))
            rest = substr(rest, 1, RSTART - 1) "_______________" substr(rest, RSTART + RLENGTH)
        }
        # 2) PANs in what is left
        while (match(rest, PAN)) {
            v = substr(rest, RSTART, RLENGTH)
            if (!(v in ok) && !(v in okpan)) report("PAN", v, substr(v, 1, 5) "****" substr(v, 10, 1))
            rest = substr(rest, 1, RSTART - 1) "__________" substr(rest, RSTART + RLENGTH)
        }
        lineno++; next
    }
    /^ / { lineno++; next }
    END { exit found }
    '
}

# Check name-status output (stdin) for new binaries. $1 = label.
scan_name_status_for_binaries() {
    local label="$1" status path found=0
    while IFS=$'\t' read -r status path rest; do
        [ -n "${status:-}" ] || continue
        case "$status" in
            A*) ;;
            R*|C*) path="$rest" ;;   # rename / copy: the destination path
            *) continue ;;
        esac
        case "$(printf '%s' "$path" | tr '[:upper:]' '[:lower:]')" in
            *.pdf|*.docx|*.xlsx)
                if ! printf '%s\n' "$ALLOWED_BINS" | grep -Fxq -- "$path"; then
                    echo "${label}${path}: new binary document (.pdf/.docx/.xlsx) is not in .githooks/allowed_binaries.txt"
                    found=1
                fi ;;
        esac
    done
    return $found
}

check_one() {   # $1 = label, $2.. = diff selector args for git diff
    local label="$1"; shift
    local rc=0
    "${GIT[@]}" diff --no-color --no-ext-diff -U0 --diff-filter=ACMR "$@" | scan_diff_for_ids "$label" || rc=1
    "${GIT[@]}" diff --name-status -M -C --diff-filter=ARC "$@" | scan_name_status_for_binaries "$label" || rc=1
    return $rc
}

rc=0
case "${1:-}" in
    --staged)
        check_one "" --cached || rc=1
        ;;
    --commit)
        [ -n "${2:-}" ] || { echo "usage: $0 --commit <sha>" >&2; exit 2; }
        sha="$("${GIT[@]}" rev-parse --verify "$2^{commit}")" || exit 2
        if "${GIT[@]}" rev-parse --verify -q "$sha^" >/dev/null; then
            check_one "${sha:0:8} " "$sha^" "$sha" || rc=1
        else
            check_one "${sha:0:8} " "$("${GIT[@]}" hash-object -t tree /dev/null)" "$sha" || rc=1
        fi
        ;;
    --commits)
        [ -n "${2:-}" ] || { echo "usage: $0 --commits <A>..<B>" >&2; exit 2; }
        commits="$("${GIT[@]}" rev-list --no-merges --reverse "$2")" || exit 2
        for sha in $commits; do
            "$0" --commit "$sha" || rc=1
        done
        ;;
    *)
        echo "usage: $0 --staged | --commit <sha> | --commits <A>..<B>" >&2
        exit 2
        ;;
esac

if [ $rc -ne 0 ] && [ "${1:-}" = "--staged" ]; then
    echo ""
    echo "Commit rejected: possible real client identifiers or documents (see docs/PRIVATE_DATA.md)."
    echo "Keep real data in \$PRIVATE_SEED_DIR outside the repo. Only fake IDs may be added to .githooks/allowed_ids.txt."
fi
exit $rc
