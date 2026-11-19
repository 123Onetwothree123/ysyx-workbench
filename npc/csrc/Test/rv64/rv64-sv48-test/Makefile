# RV64 Sv48 独立裸机回归。
# 需要先用 configs/rv64_sv48_defconfig 生成并编译 direct-NPC（XLEN=64、
# HAS_MMU=y、Sv48、TLB=y），且构建目录与 Sv39 镜像分开，例如：
#   make BUILD_DIR=build/rv64-sv48 rv64_sv48_defconfig
#   make BUILD_DIR=build/rv64-sv48 -j$(nproc) build/rv64-sv48/ysyx_26030103_npc
#   make -C npc/csrc/Test/rv64/rv64-sv48-test run \
#        BIN=../../../build/rv64-sv48/ysyx_26030103_npc
include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../common.mk)
