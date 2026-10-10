#!/usr/bin/env bash
# Pre-commit Privacy Check Script
# Rejects staged changes containing real GSTIN / PAN patterns unless explicitly allowed.

set -e

# Allow-listed fake testbench GSTINs and PANs
ALLOW_LIST=("27IIIJJ1234I1Z9" "27AAAAA0000A1Z5" "27ABCDE1234F1Z5" "IIIJJ1234I" "AAAAA0000A" "ABCDE1234F")

# Get diff of staged files excluding binaries and deleted lines
STAGED_DIFF=$(git diff --cached -U0 --no-color | grep '^\+' | grep -v '^\+\+\+' || true)

if [ -z "$STAGED_DIFF" ]; then
    exit 0
fi

# Find GSTIN matches (\d{2}[A-Z]{5}\d{4}[A-Z]{1}[A-Z0-9]{1}Z[A-Z0-9]{1})
GSTIN_MATCHES=$(echo "$STAGED_DIFF" | grep -oE '[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[A-Z0-9]{1}Z[A-Z0-9]{1}' | sort -u || true)

# Find PAN matches ([A-Z]{5}\d{4}[A-Z]{1})
PAN_MATCHES=$(echo "$STAGED_DIFF" | grep -oE '[A-Z]{5}[0-9]{4}[A-Z]{1}' | sort -u || true)

ERRORS=0

for match in $GSTIN_MATCHES $PAN_MATCHES; do
    ALLOWED=0
    for allow in "${ALLOW_LIST[@]}"; do
        if [ "$match" == "$allow" ]; then
            ALLOWED=1
            break
        fi
    done
    if [ $ALLOWED -eq 0 ]; then
        echo "ERROR: Privacy check failed! Staged commit contains potential real GSTIN/PAN: $match"
        ERRORS=1
    fi
done

if [ $ERRORS -ne 0 ]; then
    echo "Aborting commit due to potential sensitive PII data."
    exit 1
fi

exit 0
