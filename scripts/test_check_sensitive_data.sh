#!/usr/bin/env bash
# Tests for scripts/check_sensitive_data.sh through the committed hook (.githooks/pre-commit), in a
# throw-away git repository. Run: bash scripts/test_check_sensitive_data.sh
#
# Test identifiers are assembled at run time so that this file itself never contains a
# GSTIN/PAN-shaped literal.

set -uo pipefail

SRC_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

pass=0; fail=0
ok()   { echo "PASS: $1"; pass=$((pass + 1)); }
bad()  { echo "FAIL: $1"; fail=$((fail + 1)); }

REAL_GSTIN="29""ABXQZ""8401""M1Z7"     # not allow-listed
REAL_PAN="BQRTZ""7712""K"              # not allow-listed
FAKE_GSTIN="27""IIIJJ""1234""I1Z9"     # allow-listed testbench ID
FAKE_PAN_IN_GSTIN="IIIJJ""1234""I"     # PAN inside the allow-listed GSTIN

cd "$WORK"
git init -q repo && cd repo
git config user.email "test@example.invalid"
git config user.name "hook test"
mkdir -p scripts .githooks
cp "$SRC_ROOT/scripts/check_sensitive_data.sh" scripts/
cp "$SRC_ROOT/.githooks/pre-commit" .githooks/
cp "$SRC_ROOT/.githooks/allowed_ids.txt" .githooks/
printf 'docs/approved_template.docx\n' > .githooks/allowed_binaries.txt
git add -A && git commit -q --no-verify -m "setup"
git config core.hooksPath .githooks

# try_commit <name> <expect: accept|reject>   (files must already be staged)
try_commit() {
    local name="$1" expect="$2" out rc
    out="$(git commit -q -m "$name" 2>&1)"; rc=$?
    if [ "$expect" = "accept" ] && [ $rc -eq 0 ]; then ok "$name"
    elif [ "$expect" = "reject" ] && [ $rc -ne 0 ]; then ok "$name"
    else bad "$name (exit $rc)"; echo "$out" | sed 's/^/    /'
    fi
    LAST_OUT="$out"
    [ $rc -ne 0 ] && git reset -q --hard HEAD
    return 0
}

# 1. real-looking GSTIN rejected, output masked, full value never printed
printf 'client: %s\n' "$REAL_GSTIN" > client.txt; git add client.txt
try_commit "real GSTIN rejected" reject
masked="${REAL_GSTIN:0:7}****${REAL_GSTIN:11:4}"
if printf '%s' "$LAST_OUT" | grep -Fq "client.txt:1: possible GSTIN $masked"; then ok "finding shows file, line and masked value"; else bad "finding format"; echo "$LAST_OUT"; fi
if printf '%s' "$LAST_OUT" | grep -q "$REAL_GSTIN"; then bad "full GSTIN printed"; else ok "full GSTIN never printed"; fi
if printf '%s' "$LAST_OUT" | grep -q "client:"; then bad "line content printed"; else ok "line content never printed"; fi
if printf '%s' "$LAST_OUT" | grep -q "possible PAN"; then bad "PAN inside rejected GSTIN reported twice"; else ok "GSTIN checked before PAN"; fi

# 2. allow-listed fake GSTIN (and the PAN inside it) accepted
printf 'gstin: %s\npan: %s\n' "$FAKE_GSTIN" "$FAKE_PAN_IN_GSTIN" > fake.txt; git add fake.txt
try_commit "allow-listed fake GSTIN and its PAN accepted" accept

# 3. standalone real-looking PAN rejected
printf 'line one\nline two\npan=%s\n' "$REAL_PAN" > pan.txt; git add pan.txt
try_commit "PAN rejected" reject
if printf '%s' "$LAST_OUT" | grep -Fq "pan.txt:3: possible PAN ${REAL_PAN:0:5}****${REAL_PAN:9:1}"; then ok "PAN finding has line number and mask"; else bad "PAN finding format"; echo "$LAST_OUT"; fi

# 4. new .docx rejected
mkdir -p docs; printf 'binary' > docs/client_notice.docx; git add docs/client_notice.docx
try_commit "new docx rejected" reject

# 5. allow-listed .docx accepted
mkdir -p docs; printf 'binary' > docs/approved_template.docx; git add docs/approved_template.docx
try_commit "allow-listed docx accepted" accept

# 6. renamed docx to a non-listed path rejected (R status)
git mv docs/approved_template.docx docs/renamed_client.docx || bad "rename setup"
try_commit "renamed docx to unlisted path rejected" reject
if printf '%s' "$LAST_OUT" | grep -Fq "docs/renamed_client.docx: new binary document"; then ok "rename reported at its new path"; else bad "rename finding"; echo "$LAST_OUT"; fi

# 7. a .PDF (upper case extension) rejected
printf 'x' > SCAN.PDF; git add SCAN.PDF
try_commit "new PDF with upper-case extension rejected" reject

# 8. only added lines are scanned: deleting a line with an ID is fine
git -c core.hooksPath=/dev/null commit -q --allow-empty -m noop
printf '%s\n' "$REAL_GSTIN" > legacy.txt; git add legacy.txt; git commit -q --no-verify -m "legacy (bypassing hook)"
: > legacy.txt; git add legacy.txt
try_commit "removing a line with an ID accepted" accept

# 9. clean commit accepted
printf 'Section 73 notice reply, nothing sensitive here.\n' > clean.txt; git add clean.txt
try_commit "clean commit accepted" accept

# 10. --commits mode (CI) finds the commit that bypassed the hook
out="$(bash scripts/check_sensitive_data.sh --commits HEAD~4..HEAD 2>&1)"; rc=$?
if [ $rc -ne 0 ] && printf '%s' "$out" | grep -q "legacy.txt:1: possible GSTIN"; then ok "--commits mode reports bypassed commit"; else bad "--commits mode"; echo "$out"; fi

echo ""
echo "check_sensitive_data tests: $pass passed, $fail failed"
[ $fail -eq 0 ]
