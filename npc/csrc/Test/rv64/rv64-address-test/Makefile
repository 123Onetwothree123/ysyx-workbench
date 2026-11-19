# RV64 裸机地址/Cache 回归。
# 需要先用 XLEN=64 / ADDRESS_WIDTH=64 / ICACHE=y / DCACHE=y 生成并编译
# direct-NPC，然后：
#   make -C npc/csrc/Test/rv64/rv64-address-test run
MARCH := rv64im
include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../common.mk)
