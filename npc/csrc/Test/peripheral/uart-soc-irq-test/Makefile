SHELL := /bin/bash
.DEFAULT_GOAL := all

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
ROOT_DIR := $(abspath $(TEST_DIR)/../../../../..)
NPC_DIR := $(ROOT_DIR)/npc
SOC_DIR := $(ROOT_DIR)/ysyxSoC
BUILD_DIR := $(TEST_DIR)/build
RTL_DIR := $(BUILD_DIR)/rtl
BIN_DIR := $(BUILD_DIR)/bin

VERILATOR ?= verilator
CROSS ?= riscv64-unknown-elf-
CC_RV32 := $(CROSS)gcc
OBJCOPY := $(CROSS)objcopy

CXXFLAGS := -std=c++20 -O2 -Wall -Wextra
VERILATOR_FLAGS := --cc --exe --build -j 0 -Wall -Wno-fatal --no-timing \
	-Wno-UNUSEDSIGNAL -Wno-PINMISSING -Wno-DECLFILENAME \
	-Wno-SYNCASYNCNET -Wno-CASEINCOMPLETE -Wno-TIMESCALEMOD \
	-Wno-SIMILARNAME -Wno-DEFPARAM

ELABORATE_CONFIG := $(abspath $(TEST_DIR)/../../common/elaborate_config.sh)
SCALA_SOURCES := $(shell find $(NPC_DIR)/src -type f -name '*.scala' | sort)
SOC_SCALA := $(shell find $(SOC_DIR)/src -type f -name '*.scala' | sort)
PERIP_VSRCS := $(shell find $(SOC_DIR)/perip -type f -name '*.v' | sort)
SOC_RTL := $(SOC_DIR)/build/ysyxSoCFull.v
NPC_CONFIG_BUILD := $(BUILD_DIR)/npc-config
SOC_CONFIG_JSON := $(NPC_CONFIG_BUILD)/generated/npc_config.typed.json
RTL_STAMP := $(RTL_DIR)/.generated
TEST_BIN := $(BIN_DIR)/uart_soc_irq_test
IMAGE := $(BUILD_DIR)/main.bin

# 与 direct-NPC UART 测试使用同一组 RV32 硬件开关，保证 CPU 端口 ABI
# 与 full-SoC 黑盒一致。公共脚本生成隔离的 typed JSON，Elaborate 只读
# NPC_CONFIG_JSON；RV32 必须显式给出 Sv32 页几何与 32 位 PTE 编码，
# 否则 MMUProfile 拒绝从当前（RV64）Kconfig 继承来的 PTE 参数。
CONFIG_ARGS := \
  CONFIG_XLEN=32 CONFIG_SOC_XLEN_FOLLOW_CPU=n CONFIG_SOC_XLEN=32 \
  CONFIG_ADDRESS_WIDTH=32 \
  CONFIG_HAS_S=y CONFIG_HAS_U=y \
  CONFIG_HAS_MMU=n CONFIG_HAS_TLB=n CONFIG_HAS_PMP=n \
  CONFIG_ICACHE=y CONFIG_DCACHE=y CONFIG_WBUF_DEPTH=4 \
  CONFIG_CACHE_BLOCK_SIZE_LOG2=5 CONFIG_CACHE_INDEX_BITS=6 \
  CONFIG_CACHE_PADDING=0 CONFIG_CHIPLINK=n \
  CONFIG_RISCV_M=n CONFIG_RISCV_A=n CONFIG_RISCV_C=n \
  CONFIG_MMU_PTE_FORMAT=riscv32 CONFIG_MMU_PTE_RISCV32=y \
  CONFIG_MMU_PTE_RISCV64=n CONFIG_MMU_LEVELS=2 \
  CONFIG_MMU_PTE_SIZE_BYTES=4 CONFIG_MMU_PTE_PPN_HIGH=31 \
  CONFIG_SDB=n CONFIG_DIFFTEST=n CONFIG_MTRACE=n CONFIG_ITRACE=n \
  CONFIG_FTRACE=n CONFIG_IRINGBUF=n CONFIG_PERF_DIC_ARCH_COUNTER=n

# 生成 typed JSON 的命令行与 elaborate_config.sh 内部一致。`+` 与
# `env -u MAKEFLAGS` 让这条在 `make -n` 下也真正产出文件：ysyxSoC 的
# Makefile 在解析期就检查 NPC_CONFIG_JSON 是否存在，而递归 make 默认
# 会继承 -n 只打印命令。
$(SOC_CONFIG_JSON): Makefile
	+env -u MAKEFLAGS $(MAKE) -C $(NPC_DIR) BUILD_DIR=$(abspath $(NPC_CONFIG_BUILD)) \
	  NPC_CONFIG_MODULE=$(abspath $(NPC_CONFIG_BUILD))/generated/npc_config.ixx \
	  NPC_CONFIG_SCALA_DEFAULTS=$(abspath $(NPC_CONFIG_BUILD))/generated/ConfigDefaults.scala \
	  $(CONFIG_ARGS) npc-config

$(RTL_STAMP): $(SCALA_SOURCES) Makefile $(ELABORATE_CONFIG) $(SOC_CONFIG_JSON)
	@mkdir -p $(RTL_DIR)
	$(ELABORATE_CONFIG) $(TEST_DIR) $(RTL_DIR) $(CONFIG_ARGS)
	@touch $@

$(SOC_RTL): $(SOC_SCALA) $(PERIP_VSRCS) $(SOC_CONFIG_JSON) $(RTL_STAMP)
	$(MAKE) -C $(SOC_DIR) NPC_CONFIG_JSON=$(SOC_CONFIG_JSON) verilog

$(IMAGE): main.S
	@mkdir -p $(BUILD_DIR)
	$(CC_RV32) -march=rv32ima_zicsr -mabi=ilp32 -nostdlib -nostartfiles \
	  -Ttext=0x20000000 -o $(BUILD_DIR)/main.elf $<
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
