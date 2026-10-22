SHELL := /bin/bash

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
NPC_DIR := $(abspath $(TEST_DIR)/../../..)
BUILD_DIR := $(TEST_DIR)/build
NPC_RTL_DIR := $(BUILD_DIR)/npc-rtl
HARNESS_RTL_DIR := $(BUILD_DIR)/trap-flush-rtl
BIN_DIR := $(BUILD_DIR)/bin

VERILATOR ?= verilator
MILL ?= mill
CXXFLAGS := -std=c++20 -O2 -Wall -Wextra -I$(TEST_DIR)
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

$(TRAP_FLUSH_BIN): precise_trap_flush_test.cpp test_common.hpp $(HARNESS_RTL_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) -I$(HARNESS_RTL_DIR) \
		--top-module PreciseTrapFlushHarness \
		--Mdir $(BUILD_DIR)/obj-trap-flush \
		-CFLAGS '$(CXXFLAGS)' -o $(abspath $@) \
		$(HARNESS_RTL_DIR)/PreciseTrapFlushHarness.sv $<

$(NPC_RTL_STAMP): $(NPC_SCALA_SOURCES) $(NPC_DIR)/build.mill Makefile
	@mkdir -p $(NPC_RTL_DIR)
	@cd $(NPC_DIR) && env \
		CACHE_BLOCK_SIZE_LOG2=4 CACHE_INDEX_BITS=5 \
		ICACHE=y DCACHE=n WBUF_DEPTH=4 CACHE_PADDING=0 CHIPLINK=n \
		BTB_BITS=4 BTB_WAYS=1 JAL_BTB_BITS=4 JAL_BTB_WAYS=1 RAS_BITS=4 \
		RISCV_M=n RISCV_A=n RISCV_C=n \
		SDB=y DIFFTEST=y MTRACE=y ITRACE=y FTRACE=y IRINGBUF=y PERF_STATS=y \
		$(MILL) --no-server runMain ysyx_26030103_Elaborate \
			--target-dir $(NPC_RTL_DIR)
	@cp $(NPC_RTL_DIR)/layers-ysyx_26030103-Verification.sv \
		$(NPC_RTL_DIR)/layers-riscv32e_npc_AXIRAM-Verification.sv
	@touch $@

$(NPC_RESET_BIN): npc_reset_map_test.cpp test_common.hpp \
		$(NPC_DIR)/rtl/riscv32e_npc_SimTop.sv $(NPC_RTL_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) -I$(NPC_RTL_DIR) \
		--top-module riscv32e_npc_SimTop \
		--Mdir $(BUILD_DIR)/obj-npc-reset \
		-CFLAGS '$(CXXFLAGS)' -o $(abspath $@) \
		$(NPC_DIR)/rtl/riscv32e_npc_SimTop.sv $(NPC_RTL_SOURCES) $<

clean:
	rm -rf $(BUILD_DIR) out
