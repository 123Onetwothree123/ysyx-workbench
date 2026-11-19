SHELL := /bin/bash

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
NPC_DIR := $(abspath $(TEST_DIR)/../../../..)
COMMON_DIR := $(TEST_DIR)/../../common
ELABORATE_CFG := $(COMMON_DIR)/elaborate_config.sh
BUILD_DIR := $(TEST_DIR)/build
RTL_DIR := $(BUILD_DIR)/rtl
OBJ_DIR := $(BUILD_DIR)/obj_dir
RTL := $(RTL_DIR)/ysyx_26030103.sv
BIN := $(OBJ_DIR)/Vysyx_26030103_EXU

SCALA_SRCS := $(shell find $(NPC_DIR)/src -type f -name '*.scala' -print)
SCALA_SRCS += $(NPC_DIR)/build.mill $(TEST_DIR)/Makefile

VERILATOR ?= verilator

.PHONY: all run clean

all: $(BIN)

run: $(BIN)
	$(BIN)

# Do not invoke npc/Makefile's "verilog" target here: it calls git_commit.
# Generate an isolated, deterministic RV32I RTL snapshot for this module test.
$(RTL): $(SCALA_SRCS) $(ELABORATE_CFG)
	mkdir -p $(RTL_DIR)
	$(ELABORATE_CFG) $(TEST_DIR) $(RTL_DIR) \
		CONFIG_XLEN=32 CONFIG_SOC_XLEN_FOLLOW_CPU=n CONFIG_SOC_XLEN=32 \
		CONFIG_ADDRESS_WIDTH=32 CONFIG_HAS_MMU=n CONFIG_HAS_TLB=n \
		CONFIG_HAS_S=y CONFIG_HAS_U=y CONFIG_HAS_S_EXTERNAL_IRQ=n \
		CONFIG_MMU_PTE_FORMAT=riscv32 CONFIG_MMU_PTE_RISCV32=y \
		CONFIG_MMU_PTE_RISCV64=n CONFIG_MMU_PTE_SIZE_BYTES=4 \
		CONFIG_MMU_PTE_PPN_HIGH=31 CONFIG_MMU_LEVELS=2 \
		CONFIG_ICACHE=n CONFIG_DCACHE=n CONFIG_WBUF_DEPTH=4 \
		CONFIG_RISCV_M=y CONFIG_RISCV_A=n CONFIG_RISCV_C=n \
		CONFIG_RISCV_F=n CONFIG_RISCV_D=n CONFIG_FPU_IMPL=none \
		CONFIG_SDB=y CONFIG_DIFFTEST=y CONFIG_PERF_DIC_ARCH_COUNTER=y

$(BIN): main.cpp $(RTL)
	$(VERILATOR) --cc --exe --build --assert -Wno-fatal \
		--top-module ysyx_26030103_EXU \
		--Mdir $(OBJ_DIR) -I$(RTL_DIR) \
		-CFLAGS "-std=c++20 -O2 -Wall -Wextra" \
		-o Vysyx_26030103_EXU \
		$(RTL) $(TEST_DIR)/main.cpp

clean:
	rm -rf $(BUILD_DIR)
