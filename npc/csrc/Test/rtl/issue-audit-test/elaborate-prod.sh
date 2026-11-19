#!/bin/bash
# 生成生产配置（.config）对应的 RTL 快照：32B 行 / 64 组 / ICache+DCache 均
# 为同步 SRAM / SRT 除法。此前的快照集（reg、16B/32sets、sram_nobypass、
# 4B 最小行、booth 配置…）都不覆盖该组合，导致 ICacheSRAM 从未被仿真。
set -eo pipefail
source "$(dirname "$0")/elaborate-common.sh"

echo "=== 生产配置快照（sram+sram, 32B/64sets, div=srt）==="
mkdir -p "$BUILD/rtl-prod"
elaborate "$BUILD/rtl-prod" "${RV32_CONFIG[@]}" "${BASE_CONFIG[@]}" \
  CONFIG_CACHE_BLOCK_SIZE_LOG2=5 CONFIG_CACHE_INDEX_BITS=6 \
  CONFIG_SDB=n CONFIG_DIFFTEST=n CONFIG_MTRACE=n CONFIG_ITRACE=n CONFIG_FTRACE=n \
  CONFIG_IRINGBUF=n CONFIG_PERF_DIC_ARCH_COUNTER=y \
  CONFIG_ICACHE_STORAGE=sram CONFIG_DCACHE_STORAGE=sram \
  CONFIG_MUL_IMPL=shift_add CONFIG_MUL_ENCODING=plain CONFIG_MUL_RADIX=4 CONFIG_MUL_WIDTH=32 \
  CONFIG_MUL_ITER_BITS=1 CONFIG_MUL_PIPELINE=0 CONFIG_MUL_SPLIT=1 CONFIG_MUL_EARLY_OUT=n \
  CONFIG_DIV_IMPL=srt CONFIG_DIV_RADIX=2 CONFIG_DIV_ITER_BITS=1 CONFIG_DIV_EARLY_OUT=n \
  2>&1 | tail -2
echo "=== 完成 ==="
