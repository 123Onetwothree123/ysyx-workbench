# RV64 Sv39 MMU 裸机回归。
# 需要先用 configs/rv64_mmu_defconfig 生成并编译 direct-NPC（XLEN=64、
# HAS_MMU=y、Sv39、TLB=y），然后：
#   make -C npc/csrc/Test/rv64/rv64-mmu-test run
include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../common.mk)
