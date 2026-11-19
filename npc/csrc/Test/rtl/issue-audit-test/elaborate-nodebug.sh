#!/bin/bash
set -eo pipefail
source "$(dirname "$0")/elaborate-common.sh"
mkdir -p "$BUILD/rtl-nodebug"
elaborate "$BUILD/rtl-nodebug" "${RV32_CONFIG[@]}" "${BASE_CONFIG[@]}" \
  CONFIG_SDB=n CONFIG_DIFFTEST=n CONFIG_MTRACE=n CONFIG_ITRACE=n CONFIG_FTRACE=n \
  CONFIG_IRINGBUF=n CONFIG_PERF_DIC_ARCH_COUNTER=n \
  2>&1 | tail -2
