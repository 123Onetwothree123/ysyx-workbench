# RV64 Sv48 独立裸机回归。
# 需要先用 configs/rv64_sv48_defconfig 生成并编译 direct-NPC（XLEN=64、
# HAS_MMU=y、Sv48、TLB=y），且构建目录与 Sv39 镜像分开，例如：
#   make BUILD_DIR=build/rv64-sv48 rv64_sv48_defconfig
#   make BUILD_DIR=build/rv64-sv48 -j$(nproc) build/rv64-sv48/ysyx_26030103_npc
#   make -C npc/csrc/Test/rv64/rv64-sv48-test run \
#        BIN=../../../build/rv64-sv48/ysyx_26030103_npc
# Sv48 镜像必须来自独立构建目录；缺失时直接失败，禁止回退到默认 Sv39 镜像。
TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
RV64_SV48_NPC_BIN := $(abspath $(TEST_DIR)/../../../../build/rv64-sv48/ysyx_26030103_npc)
BIN := $(RV64_SV48_NPC_BIN)
include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../common.mk)

.PHONY: check-sv48-binary

check-sv48-binary:
	@if [ ! -f "$(RV64_SV48_NPC_BIN)" ]; then \
	  echo "error: Sv48 NPC binary not found: $(RV64_SV48_NPC_BIN)" >&2; \
	  echo "build it first:" >&2; \
	  echo "  make -C $(abspath $(TEST_DIR)/../../../..) BUILD_DIR=build/rv64-sv48 rv64_sv48_defconfig" >&2; \
	  echo "  make -C $(abspath $(TEST_DIR)/../../../..) BUILD_DIR=build/rv64-sv48 -j\$$(nproc) build/rv64-sv48/ysyx_26030103_npc" >&2; \
	  exit 1; \
	fi

run: check-sv48-binary
