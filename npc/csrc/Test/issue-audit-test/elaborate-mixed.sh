#!/bin/bash
# 生成"每级缓存独立几何 + 独立存储介质"的混合配置快照：
#   ICache: 16B/32sets + SRAM 存储；DCache: 32B/64sets + Reg 存储。
# 用于验证 ICACHE_*/DCACHE_* 独立配置链路（既有快照都是两者同值）。
set -e
NPC_DIR=$(cd ../../.. && pwd)
BUILD=$(pwd)/build
MIXED_ENV="CACHE_BLOCK_SIZE_LOG2=4 CACHE_INDEX_BITS=5 \
ICACHE_BLOCK_SIZE_LOG2=4 ICACHE_INDEX_BITS=5 \
DCACHE_BLOCK_SIZE_LOG2=5 DCACHE_INDEX_BITS=6 \
ICACHE=y DCACHE=y WBUF_DEPTH=4 CACHE_PADDING=0 \
CHIPLINK=n BTB_BITS=4 BTB_WAYS=1 JAL_BTB_BITS=4 JAL_BTB_WAYS=1 RAS_BITS=4 \
RISCV_M=y RISCV_A=n RISCV_C=n SDB=y DIFFTEST=y MTRACE=y ITRACE=y FTRACE=y IRINGBUF=y PERF_STATS=y \
ICACHE_STORAGE=sram DCACHE_STORAGE=reg"

echo "=== 混合几何快照（ICache 16B/32sets/sram + DCache 32B/64sets/reg）==="
mkdir -p "$BUILD/rtl-mixed"
cd "$NPC_DIR" && env $MIXED_ENV \
  mill --no-server runMain ysyx_26030103_Elaborate --target-dir "$BUILD/rtl-mixed" 2>&1 | tail -2
echo "=== 完成 ==="
