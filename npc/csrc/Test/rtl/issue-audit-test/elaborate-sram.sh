#!/bin/bash
set -eo pipefail
source "$(dirname "$0")/elaborate-common.sh"
mkdir -p "$BUILD/rtl-sram"
elaborate "$BUILD/rtl-sram" "${RV32_CONFIG[@]}" "${BASE_CONFIG[@]}" \
  CONFIG_DCACHE_STORAGE=sram CONFIG_ICACHE_STORAGE=reg 2>&1 | tail -1
