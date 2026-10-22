#!/bin/bash
# 扫 JAL_BTB_BITS/WAYS, 对三条trace评估SplitJal的jal BTB容量敏感度
set -e
cd /home/abc/ysyx-workbench/npc/csrc/tools/branchsim
# 可通过环境变量 CONFIG_XLEN=32/64 覆盖；未设置时使用工具 Kconfig 的默认值。
CMAKE_CONFIG_ARGS=()
if [[ -n "${CONFIG_XLEN:-}" ]]; then
    CMAKE_CONFIG_ARGS+=("-DCONFIG_XLEN=${CONFIG_XLEN}")
fi
for cfg in "3 1" "4 1" "5 1" "3 2" "4 2"; do
    bits=${cfg% *}; ways=${cfg#* }
    sed -i "s/^CONFIG_JAL_BTB_BITS=.*/CONFIG_JAL_BTB_BITS=$bits/; s/^CONFIG_JAL_BTB_WAYS=.*/CONFIG_JAL_BTB_WAYS=$ways/" .config
    cmake -B build "${CMAKE_CONFIG_ARGS[@]}" > /dev/null 2>&1
    cmake --build build -j"$(nproc)" > /dev/null 2>&1
    echo "########## JAL_BTB_BITS=$bits JAL_BTB_WAYS=$ways ##########"
    for t in traces/coremark.txt traces/dhrystone.txt traces/microbench-ref.txt; do
        echo "===== $t ====="
        ./build/branchsim "$t" | grep -E "算法|SplitJal|SharedJal|BTFN "
    done
done
echo SWEEP_DONE
