#!/bin/bash
# 扫 JAL_BTB_BITS/WAYS, 对三条trace评估SplitJal的jal BTB容量敏感度
set -e
cd /home/abc/ysyx-workbench/npc/csrc/tools/branchsim
for cfg in "3 1" "4 1" "5 1" "3 2" "4 2"; do
    bits=${cfg% *}; ways=${cfg#* }
    sed -i "s/^CONFIG_JAL_BTB_BITS=.*/CONFIG_JAL_BTB_BITS=$bits/; s/^CONFIG_JAL_BTB_WAYS=.*/CONFIG_JAL_BTB_WAYS=$ways/" .config
    cmake -B build > /dev/null 2>&1
    cmake --build build -j"$(nproc)" > /dev/null 2>&1
    echo "########## JAL_BTB_BITS=$bits JAL_BTB_WAYS=$ways ##########"
    for t in traces/coremark.txt traces/dhrystone.txt traces/microbench-ref.txt; do
        echo "===== $t ====="
        ./build/branchsim "$t" | grep -E "算法|SplitJal|SharedJal|BTFN "
    done
done
echo SWEEP_DONE
