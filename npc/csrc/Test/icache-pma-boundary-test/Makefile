SHELL := /bin/bash

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
NPC_DIR := $(abspath $(TEST_DIR)/../../..)
BUILD_DIR := $(TEST_DIR)/build
RTL_DIR := $(BUILD_DIR)/rtl
OBJ_DIR := $(BUILD_DIR)/obj_dir
BIN := $(BUILD_DIR)/icache_pma_boundary_test
TOP := ICachePmaBoundaryHarness
RTL := $(RTL_DIR)/$(TOP).sv
STAMP := $(RTL_DIR)/.generated

VERILATOR ?= verilator
MILL ?= mill
CXXFLAGS := -std=c++20 -O2 -Wall -Wextra -I$(TEST_DIR)
VERILATOR_FLAGS := --cc --exe --build -j 0 -Wall -Wno-fatal -DSYNTHESIS

SCALA_SRCS := \
	$(TEST_DIR)/scala/ICachePmaBoundaryHarness.scala \
	$(NPC_DIR)/src/Common/ysyx_26030103_PMA.scala \
	$(NPC_DIR)/src/IFU/ysyx_26030103_ICache.scala \
	$(NPC_DIR)/src/Infra/ysyx_26030103_AXI5IO.scala \
	$(NPC_DIR)/src/Infra/ysyx_26030103_AXI5AW.scala \
	$(NPC_DIR)/src/Infra/ysyx_26030103_AXI5W.scala \
	$(NPC_DIR)/src/Infra/ysyx_26030103_AXI5B.scala \
	$(NPC_DIR)/src/Infra/ysyx_26030103_AXI5AR.scala \
	$(NPC_DIR)/src/Infra/ysyx_26030103_AXI5R.scala \
	$(TEST_DIR)/build.mill \
	$(TEST_DIR)/layers-ICachePmaBoundaryHarness-Verification.sv \
	$(TEST_DIR)/Makefile

.PHONY: all test run clean

all: $(BIN)

test run: $(BIN)
	$(BIN)

$(STAMP): $(SCALA_SRCS)
	@mkdir -p $(RTL_DIR)
	@cd $(TEST_DIR) && $(MILL) --no-server runMain \
		ysyx_26030103.test.ICachePmaBoundaryHarnessElaborate \
		--target-dir $(RTL_DIR)
	@cp $(TEST_DIR)/layers-ICachePmaBoundaryHarness-Verification.sv $(RTL_DIR)/
	@touch $@

$(BIN): main.cpp test_common.hpp $(STAMP)
	@mkdir -p $(OBJ_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) \
		-I$(RTL_DIR) \
		--top-module $(TOP) \
		--Mdir $(OBJ_DIR) \
		-CFLAGS '$(CXXFLAGS)' \
		-o $(abspath $@) \
		$(RTL) $(TEST_DIR)/main.cpp

clean:
	rm -rf $(BUILD_DIR) out
