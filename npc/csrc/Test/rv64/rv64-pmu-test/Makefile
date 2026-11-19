# RV64 架构 PMU 回归：mhpmevent/mhpmcounter、mcountinhibit、
# 用户别名、Sscofpmf 溢出与 LCOFI。
#
# 需要先用打开 PERF_RISCV_ARCH_PMU（以及 Zihpm/Sscofpmf）的配置编译
# direct-NPC，然后：
#   make -C npc/csrc/Test/rv64/rv64-pmu-test run
include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../common.mk)
