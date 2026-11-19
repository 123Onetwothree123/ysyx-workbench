#!/bin/bash
set -eo pipefail
source "$(dirname "$0")/elaborate-common.sh"
mkdir -p "$BUILD/rtl-mdu-cfg"
elaborate "$BUILD/rtl-mdu-cfg" "${RV32_CONFIG[@]}" "${BASE_CONFIG[@]}" \
  CONFIG_MUL_IMPL=shift_add CONFIG_MUL_ENCODING=booth CONFIG_MUL_RADIX=4 CONFIG_MUL_WIDTH=32 \
  CONFIG_MUL_ITER_BITS=4 CONFIG_MUL_SPLIT=2 CONFIG_MUL_PIPELINE=0 CONFIG_MUL_EARLY_OUT=y \
  CONFIG_DIV_IMPL=nonrestoring CONFIG_DIV_RADIX=4 CONFIG_DIV_ITER_BITS=2 CONFIG_DIV_EARLY_OUT=y \
  2>&1 | tail -2
