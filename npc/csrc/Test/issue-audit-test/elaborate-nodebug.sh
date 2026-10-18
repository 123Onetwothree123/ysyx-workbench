#!/bin/bash
set -e
NPC_DIR=$(cd ../../.. && pwd)
BUILD=$(pwd)/build
mkdir -p "$BUILD/rtl-nodebug"
cd "$NPC_DIR" && env CACHE_BLOCK_SIZE_LOG2=4 CACHE_INDEX_BITS=5 ICACHE=y DCACHE=y WBUF_DEPTH=4 CACHE_PADDING=0 \
CHIPLINK=n BTB_BITS=4 BTB_WAYS=1 JAL_BTB_BITS=4 JAL_BTB_WAYS=1 RAS_BITS=4 \
RV32_M=y RV32_A=n RV32_C=n SDB=n DIFFTEST=n MTRACE=n ITRACE=n FTRACE=n IRINGBUF=n PERF_STATS=n \
mill --no-server runMain ysyx_26030103_Elaborate --target-dir "$BUILD/rtl-nodebug" 2>&1 | tail -2
