SHELL := /bin/bash
.DEFAULT_GOAL := all

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
NPC_DIR := $(abspath $(TEST_DIR)/../../../..)
BUILD_DIR := $(TEST_DIR)/build
RTL_DIR := $(BUILD_DIR)/rtl
BIN_DIR := $(BUILD_DIR)/bin

VERILATOR ?= verilator
CROSS ?= riscv64-unknown-elf-
CC_RV32 := $(CROSS)gcc
OBJCOPY := $(CROSS)objcopy

CXXFLAGS := -std=c++20 -O2 -Wall -Wextra
VERILATOR_FLAGS := --cc --exe --build -j 0 -Wall -Wno-fatal -DSYNTHESIS

ELABORATE_CONFIG := $(abspath $(TEST_DIR)/../../common/elaborate_config.sh)
SCALA_SOURCES := $(shell find $(NPC_DIR)/src -type f -name '*.scala' | sort)
RTL_STAMP := $(RTL_DIR)/.generated
TEST_BIN := $(BIN_DIR)/timer_preempt_test
IMAGE := $(BUILD_DIR)/main.bin
HARNESS := $(TEST_DIR)/../common/verilator_harness.hpp

# 该测试固定使用 RV32 direct-NPC，并强制 TIMER_PATH=direct_stip：
# CLINT 的 mtime>=mtimecmp 直接拉高 mip.STIP，由 S 模式中断入口处理。
# 关闭 MMU/TLB/PMP 与 M/A/C，让回归只关注中断/CSR 路径，缩短 Verilator
# 构建时间。
# 公共脚本生成隔离的 typed JSON，Elaborate 只读 NPC_CONFIG_JSON。
# RV32 必须显式给出 Sv32 页几何与 32 位 PTE 编码，否则 MMUProfile 拒绝
# 从当前（RV64）Kconfig 继承来的 PTE 参数。
CONFIG_ARGS := \
  CONFIG_XLEN=32 CONFIG_SOC_XLEN_FOLLOW_CPU=n CONFIG_SOC_XLEN=32 \
  CONFIG_ADDRESS_WIDTH=32 \
  CONFIG_HAS_S=y CONFIG_HAS_U=y \
  CONFIG_HAS_MMU=n CONFIG_HAS_TLB=n CONFIG_HAS_PMP=n \
  CONFIG_ICACHE=y CONFIG_DCACHE=y CONFIG_WBUF_DEPTH=4 \
  CONFIG_CACHE_BLOCK_SIZE_LOG2=5 CONFIG_CACHE_INDEX_BITS=6 \
  CONFIG_CACHE_PADDING=0 CONFIG_CHIPLINK=n \
  CONFIG_RISCV_M=n CONFIG_RISCV_A=n CONFIG_RISCV_C=n \
  CONFIG_TIMER_PATH=direct_stip CONFIG_TIMER_DIRECT_STIP=y \
  CONFIG_HAS_S_TIMER_IRQ=y \
  CONFIG_MMU_PTE_FORMAT=riscv32 CONFIG_MMU_PTE_RISCV32=y \
  CONFIG_MMU_PTE_RISCV64=n CONFIG_MMU_LEVELS=2 \
  CONFIG_MMU_PTE_SIZE_BYTES=4 CONFIG_MMU_PTE_PPN_HIGH=31 \
  CONFIG_SDB=n CONFIG_DIFFTEST=n CONFIG_MTRACE=n CONFIG_ITRACE=n \
  CONFIG_FTRACE=n CONFIG_IRINGBUF=n CONFIG_PERF_DIC_ARCH_COUNTER=n

$(RTL_STAMP): $(SCALA_SOURCES) Makefile $(ELABORATE_CONFIG)
	@mkdir -p $(RTL_DIR)
	$(ELABORATE_CONFIG) $(TEST_DIR) $(RTL_DIR) $(CONFIG_ARGS)
	@touch $@

$(IMAGE): main.S
	@mkdir -p $(BUILD_DIR)
	$(CC_RV32) -march=rv32i_zicsr_zifencei -mabi=ilp32 -nostdlib -nostartfiles \
	  -Ttext=0x80000000 -o $(BUILD_DIR)/main.elf $<
	$(OBJCOPY) -O binary $(BUILD_DIR)/main.elf $@

$(TEST_BIN): main.cpp $(HARNESS) $(RTL_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) -I$(RTL_DIR) \
	  --top-module riscv32e_npc_SimTop \
	  --Mdir $(BUILD_DIR)/obj-timer-preempt \
	  -CFLAGS '$(CXXFLAGS)' -o $(abspath $@) \
	  $(NPC_DIR)/rtl/riscv32e_npc_SimTop.sv \
	  $(RTL_DIR)/ysyx_26030103_npc.sv \
	  $(RTL_DIR)/riscv32e_npc_AXIRAM.sv \
	  $(RTL_DIR)/layers-*.sv $<

.PHONY: all test clean

all: $(TEST_BIN) $(IMAGE)

test: all
	$(TEST_BIN) $(IMAGE)

clean:
	rm -rf $(BUILD_DIR)
