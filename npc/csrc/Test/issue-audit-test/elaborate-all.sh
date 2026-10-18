#!/bin/bash
# 依次生成审计所需的三份 RTL 快照
set -e
NPC_DIR=$(cd ../../.. && pwd)
BUILD=$(pwd)/build
BASE_ENV="CACHE_BLOCK_SIZE_LOG2=4 CACHE_INDEX_BITS=5 ICACHE=y DCACHE=y WBUF_DEPTH=4 CACHE_PADDING=0 \
CHIPLINK=n BTB_BITS=4 BTB_WAYS=1 JAL_BTB_BITS=4 JAL_BTB_WAYS=1 RAS_BITS=4 \
RV32_M=y RV32_A=n RV32_C=n SDB=y DIFFTEST=y MTRACE=y ITRACE=y FTRACE=y IRINGBUF=y PERF_STATS=y"

echo "=== [1/3] base(M=y, storage=reg) ==="
mkdir -p "$BUILD/rtl-base"
cd "$NPC_DIR" && env $BASE_ENV DCACHE_STORAGE=reg ICACHE_STORAGE=reg \
  mill --no-server runMain ysyx_26030103_Elaborate --target-dir "$BUILD/rtl-base" 2>&1 | tail -2

echo "=== [2/3] dcache sram_nobypass ==="
mkdir -p "$BUILD/rtl-nobypass"
cd "$NPC_DIR" && env $BASE_ENV DCACHE_STORAGE=sram_nobypass ICACHE_STORAGE=reg \
  mill --no-server runMain ysyx_26030103_Elaborate --target-dir "$BUILD/rtl-nobypass" 2>&1 | tail -2

echo "=== [3/3] block_size_log2=2（ICache/DCache 最小行）==="
mkdir -p "$BUILD/rtl-smallline"
cd "$NPC_DIR" && env CACHE_BLOCK_SIZE_LOG2=2 CACHE_INDEX_BITS=5 ICACHE=y DCACHE=y WBUF_DEPTH=4 CACHE_PADDING=0 \
  CHIPLINK=n BTB_BITS=4 BTB_WAYS=1 JAL_BTB_BITS=4 JAL_BTB_WAYS=1 RAS_BITS=4 \
  RV32_M=y RV32_A=n RV32_C=n SDB=y DIFFTEST=y MTRACE=y ITRACE=y FTRACE=y IRINGBUF=y PERF_STATS=y \
  mill --no-server runMain ysyx_26030103_Elaborate --target-dir "$BUILD/rtl-smallline" 2>&1 | tee "$BUILD/smallline-elaborate.log" | tail -3

echo "=== 全部完成 ==="
