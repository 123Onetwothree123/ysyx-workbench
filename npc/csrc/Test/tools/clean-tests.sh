#!/usr/bin/env bash
set -u

root=${1:?usage: clean-tests.sh TEST_DIR}

find "$root" -name test.json -not -path "$root/tools/*" -print0 |
while IFS= read -r -d '' f; do
    d=$(dirname "$f")
    [ -f "$d/Makefile" ] || continue
    make -s -C "$d" clean >/dev/null 2>&1 || true
done
