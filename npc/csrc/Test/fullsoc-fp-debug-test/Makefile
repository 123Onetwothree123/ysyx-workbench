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
NPC_RTL := $(RTL_DIR)/ysyx_26030103.sv
SOC_RTL := $(SOC_DIR)/build/ysyxSoCFull.v
TEST_BIN := $(BIN_DIR)/fullsoc_fp_debug_test
IMAGE := $(BUILD_DIR)/main.bin

# 与 full-SoC DiffTest 构建一致：RV32 + HardFloat F + DIFFTEST 调试口。
# 端口探针不需要缓存/MMU，保持生成和 Verilator 编译尽量小；DIFFTEST=y
# 只用于打开 NPC 的退休/trap 调试寄存器，不链接 C++ DiffTest。
$(NPC_RTL): $(SCALA_SOURCES) Makefile
	@mkdir -p $(RTL_DIR)
	@cd $(NPC_DIR) && env \
	  CACHE_BLOCK_SIZE_LOG2=4 CACHE_INDEX_BITS=4 \
	  ICACHE=n DCACHE=n WBUF_DEPTH=4 CACHE_PADDING=0 CHIPLINK=n \
	  XLEN=32 SOC_XLEN_FOLLOW_CPU=n SOC_XLEN=32 ADDRESS_WIDTH=32 \
	  MMU_ENABLED=0 MMU_TLB_ENABLED=0 \
	  RISCV_M=n RISCV_A=n RISCV_C=n RISCV_F=y RISCV_D=n \
	  FPU_IMPL=hardfloat FPU_FLEN=32 \
	  SDB=n DIFFTEST=y MTRACE=n ITRACE=n FTRACE=n IRINGBUF=n PERF_STATS=n \
	  $(MILL) --no-server runMain ysyx_26030103_Elaborate --target-dir $(RTL_DIR)

$(SOC_RTL): $(SOC_SCALA) $(PERIP_VSRCS)
	$(MAKE) -C $(SOC_DIR) CHIPLINK=n SOC_XLEN=32 \
	  APB_CPU_FREQ_MHZ=450 APB_DEVICE_FREQ_MHZ=100 APB_DELAY_SCALE_FACTOR=64 \
	  AXI_CPU_FREQ_MHZ=450 AXI_DEVICE_FREQ_MHZ=100 AXI_DELAY_SCALE_FACTOR=64 \
	  verilog

$(IMAGE): main.S
	@mkdir -p $(BUILD_DIR)
	$(CC_RV32) -march=rv32imaf_zicsr -mabi=ilp32 -nostdlib -nostartfiles \
	  -Ttext=0x30000000 -Wl,--no-relax -o $(BUILD_DIR)/main.elf $<
	$(OBJCOPY) -O binary $(BUILD_DIR)/main.elf $@

$(TEST_BIN): main.cpp $(NPC_RTL) $(SOC_RTL)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) \
	  -I$(RTL_DIR) -I$(SOC_DIR)/perip/uart16550/rtl -I$(SOC_DIR)/perip/spi/rtl \
	  --top-module ysyxSoCFull \
	  --Mdir $(BUILD_DIR)/obj-fullsoc-fp-debug \
	  -CFLAGS '$(CXXFLAGS)' -o $(abspath $@) \
	  $(NPC_RTL) $(SOC_RTL) $(PERIP_VSRCS) $<

.PHONY: all probe diff clean

all: $(TEST_BIN) $(IMAGE)

probe: $(TEST_BIN) $(IMAGE)
	$(TEST_BIN) $(IMAGE)

# 使用完整的 NPC full-SoC 仿真器与 NEMU REF，验证逐退休 FPR/fcsr 比对
# 以及 trap tval 注入。BIN 默认对应 `make rv32_f_defconfig` 后
# `make SIM_TOP=soc` 生成的二进制。
BIN ?= $(NPC_DIR)/build/ysyx_26030103
REF ?= $(ROOT_DIR)/nemu/build/riscv32-nemu-interpreter-so
DIFF_LOG := $(BUILD_DIR)/diff.log

diff: $(IMAGE)
	@test -x "$(BIN)" || { echo "缺少 full-SoC NPC: $(BIN)"; exit 1; }
	@test -f "$(REF)" || { echo "缺少 RV32 NEMU REF: $(REF)"; exit 1; }
	@mkdir -p $(BUILD_DIR)
	@set +e; \
	  $(BIN) $(IMAGE) --diff $(REF) >$(DIFF_LOG) 2>&1; \
	  status=$$?; set -e; cat $(DIFF_LOG); \
	  test $$status -eq 0 || { echo "full-SoC FP DiffTest 运行失败"; exit 1; }; \
	  grep -Fq 'DiffTest: 浮点状态 ABI ON' $(DIFF_LOG) || \
	    { echo "未启用逐 f 寄存器/fcsr 比对"; exit 1; }; \
	  grep -Fq 'DiffTest: trap tval ABI ON' $(DIFF_LOG) || \
	    { echo "full-SoC 未启用 trap tval 注入"; exit 1; }; \
	  ! grep -Fq 'tval 注入降级' $(DIFF_LOG) || \
	    { echo "full-SoC 仍在降级 tval 注入"; exit 1; }; \
	  grep -Fq 'DiffTest: final state PASS' $(DIFF_LOG) || \
	    { echo "DiffTest 未完成最终状态比对"; exit 1; }; \
	  grep -Fq 'HIT GOOD TRAP' $(DIFF_LOG) || \
	    { echo "guest 未触发 HIT GOOD TRAP"; exit 1; }; \
	  ! grep -Fq 'DiffTest 校验失败' $(DIFF_LOG) || \
	    { echo "DiffTest 出现分叉"; exit 1; }; \
	  echo "full-SoC FP/trap DiffTest PASS"

clean:
	rm -rf $(BUILD_DIR)
