#!/bin/bash
# 生成各benchmark最大规模的btrace, 并用branchsim评估
set -x
cd /home/abc/ysyx-workbench
TRACES=npc/csrc/tools/branchsim/traces
mkdir -p "$TRACES"

run_bench() {
    local name="$1"; shift
    local dir="$1"; shift
    make -C "$dir" ARCH=riscv32-nemu run "$@" || return 1
    cp nemu/build/btrace.txt "$TRACES/$name.txt"
    wc -l "$TRACES/$name.txt"
}

# 注意: microbench huge 档在NEMU上trace增长约139MB/s且无界(50s即17GB), 不可行;
# 用ref档(ysyx在NEMU上的标准评估规模)代替
run_bench microbench-ref am-kernels/benchmarks/microbench mainargs=ref
run_bench coremark       am-kernels/benchmarks/coremark
run_bench dhrystone      am-kernels/benchmarks/dhrystone

cd npc/csrc/tools/branchsim
for t in traces/*.txt; do
    echo "===== $t ====="
    ./build/branchsim "$t"
done
echo ALL_DONE
