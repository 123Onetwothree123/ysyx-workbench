#!/usr/bin/env bash
# gen_ports.sh <VTop.h> <out.hpp> [namespace]
set -euo pipefail
hdr=$1
out=$2
top=$(basename "$hdr" .h)
ns=${3:-$top}
{
  echo '#pragma once'
  echo "#include \"$(basename "$hdr")\""
  echo
  echo "namespace ports::$ns {"
  grep -oE 'VL_(IN|OUT|INOUT)(8|16|32|64)?\(&[A-Za-z_][A-Za-z0-9_]*' "$hdr" \
    | sed 's/.*&//' | sort -u \
    | while read -r name; do
        echo "inline auto &$name($top &dut) { return dut.$name; }"
      done
  echo '}'
} > "$out"
