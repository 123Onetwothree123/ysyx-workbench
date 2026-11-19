SHELL := /bin/bash
.DEFAULT_GOAL := all

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
NPC_DIR := $(abspath $(TEST_DIR)/../../../..)
VGA_GUEST_DIR := $(abspath $(TEST_DIR)/../vga-test)
VGA_GUEST := $(VGA_GUEST_DIR)/build/vga-test-riscv32i-ysyxsoc.bin
BUILD_DIR := $(TEST_DIR)/build
NPC_BUILD_DIR := $(BUILD_DIR)/npc-build
NPC_CONFIG_JSON := $(NPC_BUILD_DIR)/generated/npc_config.typed.json
# Scala 默认值写到隔离目录，避免覆盖产品树里已提交的
# src/generated/ysyx_26030103_ConfigDefaults.scala：typed JSON 已携带本次
# 配置的全部键，elaboration 不需要改产品默认值。
NPC_CONFIG_SCALA_DEFAULTS := $(NPC_BUILD_DIR)/generated/ConfigDefaults.scala
NPC_BIN := $(NPC_BUILD_DIR)/ysyx_26030103
RUN_DIR := $(BUILD_DIR)/run

# Y=36/X=146 in the first complete raster frame.  At this visible sample the
# test-only build inverts both HSYNC and VALID, producing a one-cycle sync
# glitch and one genuinely missing visible coordinate.
FAULT_CYCLE := 28145
TEST_CXXFLAGS := -std=c++26 -fmodules \
	-DNPC_VGA_CHECK_TEST_INJECT_CYCLE=$(FAULT_CYCLE)

# 客户机是 riscv32i-ysyxsoc，因此 full-SoC 仿真器必须用 RV32 配置，
# 否则 CPU 端口位宽和客户机 ABI 不一致。RV32 还要显式给出 Sv32 页几何
# 与 32 位 PTE 编码，避免从当前（RV64）Kconfig 继承 PTE 参数后被
# MMUProfile 拒绝。
CONFIG_ARGS := \
  CONFIG_XLEN=32 CONFIG_SOC_XLEN_FOLLOW_CPU=n CONFIG_SOC_XLEN=32 \
  CONFIG_ADDRESS_WIDTH=32 \
  CONFIG_HAS_MMU=n CONFIG_HAS_TLB=n CONFIG_HAS_PMP=n \
  CONFIG_ICACHE=y CONFIG_DCACHE=y CONFIG_WBUF_DEPTH=4 \
  CONFIG_CACHE_BLOCK_SIZE_LOG2=5 CONFIG_CACHE_INDEX_BITS=6 \
  CONFIG_CACHE_PADDING=0 CONFIG_CHIPLINK=n \
  CONFIG_RISCV_M=n CONFIG_RISCV_A=n CONFIG_RISCV_C=n \
  CONFIG_MMU_PTE_FORMAT=riscv32 CONFIG_MMU_PTE_RISCV32=y \
  CONFIG_MMU_PTE_RISCV64=n CONFIG_MMU_LEVELS=2 \
  CONFIG_MMU_PTE_SIZE_BYTES=4 CONFIG_MMU_PTE_PPN_HIGH=31 \
  CONFIG_SDB=n CONFIG_DIFFTEST=n CONFIG_PERF_DIC_ARCH_COUNTER=n \
  CONFIG_PERF_SAVE=n CONFIG_LOG_LEVEL=0 CONFIG_LOG_TO_FILE=n

.PHONY: all test clean guest simulator

all: test

guest:
	$(MAKE) -C $(VGA_GUEST_DIR) ARCH=riscv32i-ysyxsoc

# full-SoC 构建链在 ysyxSoC 的 Makefile 解析期就要求 typed JSON 存在。
# `+` 与 `env -u MAKEFLAGS` 让这条在 `make -n` 下也真正产出文件（递归
# make 默认继承 -n，只打印不执行）。SIM_TOP=soc 走 ysyxSoCFull 顶层。
$(NPC_CONFIG_JSON): Makefile
	+env -u MAKEFLAGS $(MAKE) -C $(NPC_DIR) git_commit= SIM_TOP=soc \
	  $(CONFIG_ARGS) \
	  NPC_CONFIG_SCALA_DEFAULTS=$(abspath $(NPC_CONFIG_SCALA_DEFAULTS)) \
	  BUILD_DIR=$(abspath $(NPC_BUILD_DIR)) npc-config

simulator: $(NPC_CONFIG_JSON)
	$(MAKE) -C $(NPC_DIR) git_commit= SIM_TOP=soc \
	  $(CONFIG_ARGS) \
	  NPC_CONFIG_SCALA_DEFAULTS=$(abspath $(NPC_CONFIG_SCALA_DEFAULTS)) \
	  USER_CXXFLAGS='$(TEST_CXXFLAGS)' \
	  BUILD_DIR=$(abspath $(NPC_BUILD_DIR)) $(abspath $(NPC_BIN))

test: guest simulator
	rm -rf $(RUN_DIR)
	mkdir -p $(RUN_DIR)
	@cd $(RUN_DIR); set +e; \
	  $(abspath $(NPC_BIN)) $(VGA_GUEST) --vga-check > output.log 2>&1; \
	  status=$$?; set -e; test $$status -eq 1
	grep -Eq 'HSYNC脉宽异常.*: [1-9][0-9]*' $(RUN_DIR)/output.log
	grep -Eq 'HSYNC光栅电平错误拍数: [1-9][0-9]*' $(RUN_DIR)/output.log
	grep -Eq '有效区/porch时序错误拍数: [1-9][0-9]*' $(RUN_DIR)/output.log
	grep -Eq '累计缺失/重复像素: [1-9][0-9]*/[0-9]+' $(RUN_DIR)/output.log
	grep -Fq 'VGA时序检查: FAIL' $(RUN_DIR)/output.log
	grep -Fq 'HIT BAD TRAP' $(RUN_DIR)/output.log
	@echo '[PASS] VGA checker rejects injected sync and pixel-coverage faults'

clean:
	rm -rf $(BUILD_DIR)
