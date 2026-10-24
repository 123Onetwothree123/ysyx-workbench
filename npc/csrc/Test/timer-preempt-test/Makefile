SHELL := /bin/bash

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
NPC_DIR := $(abspath $(TEST_DIR)/../../..)
BUILD_DIR := $(TEST_DIR)/build
RTL_DIR := $(BUILD_DIR)/rtl
BIN_DIR := $(BUILD_DIR)/bin

MILL ?= mill
VERILATOR ?= verilator
CROSS ?= riscv64-unknown-elf-
CC_RV32 := $(CROSS)gcc
OBJCOPY := $(CROSS)objcopy

CXXFLAGS := -std=c++20 -O2 -Wall -Wextra
VERILATOR_FLAGS := --cc --exe --build -j 0 -Wall -Wno-fatal -DSYNTHESIS

SCALA_SOURCES := $(shell find $(NPC_DIR)/src -type f -name '*.scala' | sort)
RTL_STAMP := $(RTL_DIR)/.generated
TEST_BIN := $(BIN_DIR)/timer_preempt_test
IMAGE := $(BUILD_DIR)/main.bin

# 该测试固定使用 RV32 direct-NPC，并强制 TIMER_PATH=direct_stip：
# CLINT 的 mtime>=mtimecmp 直接拉高 mip.STIP，由 S 模式中断入口处理。
# 关闭 MMU/M/A/C，让回归只关注中断/CSR 路径，缩短 Verilator 构建时间。
$(RTL_STAMP): $(SCALA_SOURCES) Makefile
	@mkdir -p $(RTL_DIR)
	@cd $(NPC_DIR) && env \
	  CACHE_BLOCK_SIZE_LOG2=5 CACHE_INDEX_BITS=6 \
	  ICACHE=y DCACHE=y WBUF_DEPTH=4 CACHE_PADDING=0 CHIPLINK=n \
	  XLEN=32 SOC_XLEN_FOLLOW_CPU=n SOC_XLEN=32 ADDRESS_WIDTH=32 \
	  MMU_ENABLED=0 MMU_TLB_ENABLED=0 \
	  RISCV_M=n RISCV_A=n RISCV_C=n \
	  TIMER_PATH=direct_stip HAS_S_TIMER_IRQ=y \
	  SDB=n DIFFTEST=n MTRACE=n ITRACE=n FTRACE=n IRINGBUF=n PERF_STATS=n \
	  $(MILL) --no-server runMain ysyx_26030103_Elaborate --target-dir $(RTL_DIR)
	@touch $@

$(IMAGE): main.S
	@mkdir -p $(BUILD_DIR)
	$(CC_RV32) -march=rv32i_zicsr_zifencei -mabi=ilp32 -nostdlib -nostartfiles \
	  -Ttext=0x80000000 -o $(BUILD_DIR)/main.elf $<
	$(OBJCOPY) -O binary $(BUILD_DIR)/main.elf $@

$(TEST_BIN): main.cpp $(RTL_STAMP)
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
