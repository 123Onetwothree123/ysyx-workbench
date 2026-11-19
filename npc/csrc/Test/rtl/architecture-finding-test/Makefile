SHELL := /bin/bash

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
COMMON_DIR := $(TEST_DIR)/../../common
NPC_DIR := $(abspath $(TEST_DIR)/../../../..)
ELABORATE_CFG := $(COMMON_DIR)/elaborate_config.sh
BUILD_DIR := $(TEST_DIR)/build
NPC_RTL_DIR := $(BUILD_DIR)/npc-rtl
HARNESS_RTL_DIR := $(BUILD_DIR)/trap-flush-rtl
BIN_DIR := $(BUILD_DIR)/bin

VERILATOR ?= verilator
MILL ?= mill
CXXFLAGS := -std=c++20 -O2 -Wall -Wextra -I$(TEST_DIR) -I$(COMMON_DIR)
VERILATOR_FLAGS := --cc --exe --build -j 0 -Wall -Wno-fatal -DSYNTHESIS

NPC_SCALA_SOURCES := $(shell find $(NPC_DIR)/src -type f -name '*.scala' | sort)
LOCAL_SCALA_SOURCES := $(shell find $(TEST_DIR)/scala -type f -name '*.scala' | sort)
NPC_RTL_STAMP := $(NPC_RTL_DIR)/.generated
HARNESS_RTL_STAMP := $(HARNESS_RTL_DIR)/.generated

TRAP_FLUSH_BIN := $(BIN_DIR)/precise_trap_flush_test
NPC_RESET_BIN := $(BIN_DIR)/npc_reset_map_test
NPC_RTL_SOURCES = $(shell find $(NPC_RTL_DIR) -maxdepth 1 -type f -name '*.sv' \
		! -name 'ysyx_26030103.sv' 2>/dev/null | sort)

.PHONY: all test run trap-flush-test npc-reset-test clean

all: $(TRAP_FLUSH_BIN) $(NPC_RESET_BIN)

# Both programs assert the architecturally correct behavior.  The reset-map
# test also executes the first RAM instruction through the direct-NPC wrapper.
test run: all
	@status=0; \
	  $(TRAP_FLUSH_BIN) || status=1; \
	  $(NPC_RESET_BIN) || status=1; \
	  exit $$status

trap-flush-test: $(TRAP_FLUSH_BIN)
	$(TRAP_FLUSH_BIN)

npc-reset-test: $(NPC_RESET_BIN)
	$(NPC_RESET_BIN)

$(HARNESS_RTL_STAMP): build.mill $(LOCAL_SCALA_SOURCES) $(NPC_SCALA_SOURCES) \
		layers-PreciseTrapFlushHarness-Verification.sv \
		layers-ysyx_26030103_LSU-Verification.sv \
		layers-ysyx_26030103_LSU-Verification-Assert.sv
	@mkdir -p $(HARNESS_RTL_DIR)
	@cd $(TEST_DIR) && MILL_WORKSPACE_ROOT=$(TEST_DIR) $(MILL) --no-server runMain \
		ysyx_26030103.test.PreciseTrapFlushHarnessElaborate \
		--target-dir $(HARNESS_RTL_DIR)
	@cp $(TEST_DIR)/layers-PreciseTrapFlushHarness-Verification.sv $(HARNESS_RTL_DIR)/
	@cp $(TEST_DIR)/layers-ysyx_26030103_LSU-Verification.sv $(HARNESS_RTL_DIR)/
	@cp $(TEST_DIR)/layers-ysyx_26030103_LSU-Verification-Assert.sv $(HARNESS_RTL_DIR)/
	@touch $@

$(TRAP_FLUSH_BIN): precise_trap_flush_test.cpp $(COMMON_DIR)/test_common.hpp $(HARNESS_RTL_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) -I$(HARNESS_RTL_DIR) \
		--top-module PreciseTrapFlushHarness \
		--Mdir $(BUILD_DIR)/obj-trap-flush \
		-CFLAGS '$(CXXFLAGS)' -o $(abspath $@) \
		$(HARNESS_RTL_DIR)/PreciseTrapFlushHarness.sv $<

$(NPC_RTL_STAMP): $(NPC_SCALA_SOURCES) $(NPC_DIR)/build.mill Makefile $(ELABORATE_CFG)
	@mkdir -p $(NPC_RTL_DIR)
	@$(ELABORATE_CFG) $(TEST_DIR) $(NPC_RTL_DIR) \
		CONFIG_XLEN=32 CONFIG_SOC_XLEN_FOLLOW_CPU=n CONFIG_SOC_XLEN=32 \
		CONFIG_ADDRESS_WIDTH=32 CONFIG_HAS_MMU=n CONFIG_HAS_TLB=n \
		CONFIG_MMU_PTE_FORMAT=riscv32 CONFIG_MMU_PTE_RISCV32=y \
		CONFIG_MMU_PTE_RISCV64=n CONFIG_MMU_PTE_SIZE_BYTES=4 \
		CONFIG_MMU_PTE_PPN_HIGH=31 CONFIG_MMU_LEVELS=2 \
		CONFIG_CACHE_BLOCK_SIZE_LOG2=4 CONFIG_CACHE_INDEX_BITS=5 \
		CONFIG_ICACHE=y CONFIG_DCACHE=n CONFIG_WBUF_DEPTH=4 CONFIG_CACHE_PADDING=0 CONFIG_CHIPLINK=n \
		CONFIG_BTB_BITS=4 CONFIG_BTB_WAYS=1 CONFIG_JAL_BTB_BITS=4 CONFIG_JAL_BTB_WAYS=1 CONFIG_RAS_BITS=4 \
		CONFIG_RISCV_M=n CONFIG_RISCV_A=n CONFIG_RISCV_C=n \
		CONFIG_RISCV_F=n CONFIG_RISCV_D=n CONFIG_FPU_IMPL=none \
		CONFIG_SDB=y CONFIG_DIFFTEST=y CONFIG_MTRACE=y CONFIG_ITRACE=y CONFIG_FTRACE=y \
		CONFIG_IRINGBUF=y CONFIG_PERF_DIC_ARCH_COUNTER=y
	@cp $(NPC_RTL_DIR)/layers-ysyx_26030103-Verification.sv \
		$(NPC_RTL_DIR)/layers-riscv32e_npc_AXIRAM-Verification.sv
	@touch $@

$(NPC_RESET_BIN): npc_reset_map_test.cpp $(COMMON_DIR)/test_common.hpp \
		$(NPC_DIR)/rtl/riscv32e_npc_SimTop.sv $(NPC_RTL_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) -I$(NPC_RTL_DIR) \
		--top-module riscv32e_npc_SimTop \
		--Mdir $(BUILD_DIR)/obj-npc-reset \
		-CFLAGS '$(CXXFLAGS)' -o $(abspath $@) \
		$(NPC_DIR)/rtl/riscv32e_npc_SimTop.sv $(NPC_RTL_SOURCES) $<

clean:
	rm -rf $(BUILD_DIR) out
