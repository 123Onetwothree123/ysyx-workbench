#!/bin/bash
set -eo pipefail
source "$(dirname "$0")/elaborate-common.sh"
mkdir -p "$BUILD/rtl-btb2"
elaborate "$BUILD/rtl-btb2" "${RV32_CONFIG[@]}" "${BASE_CONFIG[@]}" \
  CONFIG_BTB_BITS=2 CONFIG_BTB_WAYS=2 CONFIG_JAL_BTB_BITS=2 CONFIG_JAL_BTB_WAYS=2 \
  2>&1 | tail -2
