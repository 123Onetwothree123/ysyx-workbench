SHELL := /bin/bash

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
ROOT_DIR := $(abspath $(TEST_DIR)/../../../..)
NPC_DIR := $(ROOT_DIR)/npc
SOC_DIR := $(ROOT_DIR)/ysyxSoC
BUILD_DIR := $(TEST_DIR)/build
RTL_DIR := $(BUILD_DIR)/rtl
BIN_DIR := $(BUILD_DIR)/bin

MILL ?= mill
VERILATOR ?= verilator
CROSS ?= riscv64-unknown-elf-
CC_RV32 := $(CROSS)gcc
OBJCOPY := $(CROSS)objcopy

CXXFLAGS := -std=c++20 -O2 -Wall -Wextra
VERILATOR_FLAGS := --cc --exe --build -j 0 -Wall -Wno-fatal --no-timing \
	-Wno-UNUSEDSIGNAL -Wno-PINMISSING -Wno-DECLFILENAME \
	-Wno-SYNCASYNCNET -Wno-CASEINCOMPLETE -Wno-TIMESCALEMOD \
	-Wno-SIMILARNAME -Wno-DEFPARAM

SCALA_SOURCES := $(shell find $(NPC_DIR)/src -type f -name '*.scala' | sort)
SOC_SCALA := $(shell find $(SOC_DIR)/src -type f -name '*.scala' | sort)
PERIP_VSRCS := $(shell find $(SOC_DIR)/perip -type f -name '*.v' | sort)
SOC_RTL := $(SOC_DIR)/build/ysyxSoCFull.v
RTL_STAMP := $(RTL_DIR)/.generated
TEST_BIN := $(BIN_DIR)/uart_soc_irq_test
IMAGE := $(BUILD_DIR)/main.bin

# 与 direct-NPC UART 测试使用同一组 RV32 硬件开关，保证 CPU 端口 ABI
# 与 full-SoC 黑盒一致。
$(RTL_STAMP): $(SCALA_SOURCES) Makefile
	@mkdir -p $(RTL_DIR)
	@cd $(NPC_DIR) && env \
	  CACHE_BLOCK_SIZE_LOG2=5 CACHE_INDEX_BITS=6 \
	  ICACHE=y DCACHE=y WBUF_DEPTH=4 CACHE_PADDING=0 CHIPLINK=n \
	  XLEN=32 SOC_XLEN_FOLLOW_CPU=n SOC_XLEN=32 ADDRESS_WIDTH=32 \
	  MMU_ENABLED=0 MMU_TLB_ENABLED=0 \
	  RISCV_M=n RISCV_A=n RISCV_C=n \
	  SDB=n DIFFTEST=n MTRACE=n ITRACE=n FTRACE=n IRINGBUF=n PERF_STATS=n \
	  $(MILL) --no-server runMain ysyx_26030103_Elaborate --target-dir $(RTL_DIR)
	@touch $@

$(SOC_RTL): $(SOC_SCALA) $(PERIP_VSRCS)
	$(MAKE) -C $(SOC_DIR) CHIPLINK=n SOC_XLEN=32 \
	  APB_CPU_FREQ_MHZ=450 APB_DEVICE_FREQ_MHZ=100 APB_DELAY_SCALE_FACTOR=64 \
	  AXI_CPU_FREQ_MHZ=450 AXI_DEVICE_FREQ_MHZ=100 AXI_DELAY_SCALE_FACTOR=64 \
	  verilog

$(IMAGE): main.S
	@mkdir -p $(BUILD_DIR)
	$(CC_RV32) -march=rv32ima_zicsr -mabi=ilp32 -nostdlib -nostartfiles \
	  -Ttext=0x30000000 -o $(BUILD_DIR)/main.elf $<
	$(OBJCOPY) -O binary $(BUILD_DIR)/main.elf $@

$(TEST_BIN): main.cpp $(RTL_STAMP) $(SOC_RTL)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) \
	  -I$(RTL_DIR) -I$(SOC_DIR)/perip/uart16550/rtl -I$(SOC_DIR)/perip/spi/rtl \
	  --top-module ysyxSoCFull \
	  --Mdir $(BUILD_DIR)/obj-uart-soc-irq \
	  -CFLAGS '$(CXXFLAGS)' -o $(abspath $@) \
	  $(RTL_DIR)/ysyx_26030103.sv $(SOC_RTL) $(PERIP_VSRCS) $<

.PHONY: all test clean

all: $(TEST_BIN) $(IMAGE)

test: all
	$(TEST_BIN) $(IMAGE)

clean:
	rm -rf $(BUILD_DIR)
