#!/bin/bash
set -e
NPC_DIR=$(cd ../../.. && pwd)
BUILD=$(pwd)/build
mkdir -p "$BUILD/rtl-sram"
cd "$NPC_DIR" && env CACHE_BLOCK_SIZE_LOG2=4 CACHE_INDEX_BITS=5 ICACHE=y DCACHE=y WBUF_DEPTH=4 CACHE_PADDING=0 \
CHIPLINK=n BTB_BITS=4 BTB_WAYS=1 JAL_BTB_BITS=4 JAL_BTB_WAYS=1 RAS_BITS=4 \
RISCV_M=y RISCV_A=n RISCV_C=n SDB=y DIFFTEST=y MTRACE=y ITRACE=y FTRACE=y IRINGBUF=y PERF_STATS=y \
DCACHE_STORAGE=sram ICACHE_STORAGE=reg \
mill --no-server runMain ysyx_26030103_Elaborate --target-dir "$BUILD/rtl-sram" 2>&1 | tail -1
