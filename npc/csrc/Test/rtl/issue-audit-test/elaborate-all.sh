#!/bin/bash
# 依次生成审计所需的三份 RTL 快照
set -eo pipefail
source "$(dirname "$0")/elaborate-common.sh"

echo "=== [1/3] base(M=y, storage=reg) ==="
mkdir -p "$BUILD/rtl-base"
elaborate "$BUILD/rtl-base" "${RV32_CONFIG[@]}" "${BASE_CONFIG[@]}" \
  CONFIG_DCACHE_STORAGE=reg CONFIG_ICACHE_STORAGE=reg 2>&1 | tail -2

echo "=== [2/3] dcache sram_nobypass ==="
mkdir -p "$BUILD/rtl-nobypass"
elaborate "$BUILD/rtl-nobypass" "${RV32_CONFIG[@]}" "${BASE_CONFIG[@]}" \
  CONFIG_DCACHE_STORAGE=sram_nobypass CONFIG_ICACHE_STORAGE=reg 2>&1 | tail -2

echo "=== [3/3] block_size_log2=2（ICache/DCache 最小行）==="
mkdir -p "$BUILD/rtl-smallline"
elaborate "$BUILD/rtl-smallline" "${RV32_CONFIG[@]}" "${BASE_CONFIG[@]}" \
  CONFIG_CACHE_BLOCK_SIZE_LOG2=2 2>&1 \
  | tee "$BUILD/smallline-elaborate.log" | tail -3

echo "=== 全部完成 ==="
