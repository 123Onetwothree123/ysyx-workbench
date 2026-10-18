#!/bin/bash
# 生成生产配置（.config）对应的 RTL 快照：32B 行 / 64 组 / ICache+DCache 均
# 为同步 SRAM / SRT 除法。此前的快照集（reg、16B/32sets、sram_nobypass、
# 4B 最小行、booth 配置…）都不覆盖该组合，导致 ICacheSRAM 从未被仿真。
set -e
NPC_DIR=$(cd ../../.. && pwd)
BUILD=$(pwd)/build
PROD_ENV="CACHE_BLOCK_SIZE_LOG2=5 CACHE_INDEX_BITS=6 ICACHE=y DCACHE=y WBUF_DEPTH=4 CACHE_PADDING=0 \
CHIPLINK=n BTB_BITS=4 BTB_WAYS=1 JAL_BTB_BITS=4 JAL_BTB_WAYS=1 RAS_BITS=4 \
RV32_M=y RV32_A=n RV32_C=n SDB=n DIFFTEST=n MTRACE=n ITRACE=n FTRACE=n IRINGBUF=n PERF_STATS=y \
ICACHE_STORAGE=sram DCACHE_STORAGE=sram \
MUL_IMPL=shift_add MUL_ENCODING=plain MUL_RADIX=4 MUL_WIDTH=32 MUL_ITER_BITS=1 MUL_PIPELINE=0 MUL_SPLIT=1 MUL_EARLY_OUT=n \
DIV_IMPL=srt DIV_RADIX=2 DIV_ITER_BITS=1 DIV_EARLY_OUT=n"

echo "=== 生产配置快照（sram+sram, 32B/64sets, div=srt）==="
mkdir -p "$BUILD/rtl-prod"
cd "$NPC_DIR" && env $PROD_ENV \
  mill --no-server runMain ysyx_26030103_Elaborate --target-dir "$BUILD/rtl-prod" 2>&1 | tail -2
echo "=== 完成 ==="
