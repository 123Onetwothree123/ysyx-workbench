#!/bin/bash
# 生成"每级缓存独立几何 + 独立存储介质"的混合配置快照：
#   ICache: 16B/32sets + SRAM 存储；DCache: 32B/64sets + Reg 存储。
# 用于验证 ICACHE_*/DCACHE_* 独立配置链路（既有快照都是两者同值）。
set -eo pipefail
source "$(dirname "$0")/elaborate-common.sh"

echo "=== 混合几何快照（ICache 16B/32sets/sram + DCache 32B/64sets/reg）==="
mkdir -p "$BUILD/rtl-mixed"
elaborate "$BUILD/rtl-mixed" "${RV32_CONFIG[@]}" "${BASE_CONFIG[@]}" \
  CONFIG_CACHE_BLOCK_SIZE_LOG2=4 CONFIG_CACHE_INDEX_BITS=5 \
  CONFIG_ICACHE_BLOCK_SIZE_LOG2=4 CONFIG_ICACHE_INDEX_BITS=5 \
  CONFIG_DCACHE_BLOCK_SIZE_LOG2=5 CONFIG_DCACHE_INDEX_BITS=6 \
  CONFIG_ICACHE_STORAGE=sram CONFIG_DCACHE_STORAGE=reg \
  2>&1 | tail -2
echo "=== 完成 ==="
