SHELL := /bin/bash

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
NPC_DIR := $(abspath $(TEST_DIR)/../../..)
BUILD_DIR := $(TEST_DIR)/build
RTL_DIR := $(BUILD_DIR)/rtl
OBJ_DIR := $(BUILD_DIR)/obj
TOP := LSUAccessFaultBackpressureHarness
BIN := $(BUILD_DIR)/lsu_access_fault_backpressure_test

VERILATOR ?= verilator
MILL ?= mill
CXXFLAGS := -std=c++20 -O2 -Wall -Wextra -I$(TEST_DIR)

LOCAL_SCALA_SOURCES := $(shell find $(TEST_DIR)/scala -type f -name '*.scala' | sort)
NPC_SCALA_SOURCES := \
	$(NPC_DIR)/src/Common/ysyx_26030103_EXUMessage.scala \
	$(NPC_DIR)/src/Common/ysyx_26030103_PMA.scala \
	$(NPC_DIR)/src/MEM/ysyx_26030103_DCache.scala \
	$(NPC_DIR)/src/MEM/ysyx_26030103_LSU.scala \
	$(NPC_DIR)/src/Infra/ysyx_26030103_AXI5IO.scala \
	$(NPC_DIR)/src/Infra/ysyx_26030103_AXI5AW.scala \
	$(NPC_DIR)/src/Infra/ysyx_26030103_AXI5W.scala \
	$(NPC_DIR)/src/Infra/ysyx_26030103_AXI5B.scala \
	$(NPC_DIR)/src/Infra/ysyx_26030103_AXI5AR.scala \
	$(NPC_DIR)/src/Infra/ysyx_26030103_AXI5R.scala
RTL_STAMP := $(RTL_DIR)/.generated

.PHONY: all test run clean

all: $(BIN)

test run: $(BIN)
	@$(BIN)

$(RTL_STAMP): build.mill $(LOCAL_SCALA_SOURCES) $(NPC_SCALA_SOURCES) \
		layers-LSUAccessFaultBackpressureHarness-Verification.sv
	@mkdir -p $(RTL_DIR)
	@cd $(TEST_DIR) && $(MILL) --no-server runMain \
		ysyx_26030103.test.LSUAccessFaultBackpressureHarnessElaborate \
		--target-dir $(RTL_DIR)
	@cp $(TEST_DIR)/layers-LSUAccessFaultBackpressureHarness-Verification.sv \
		$(RTL_DIR)/
	@touch $@

$(BIN): main.cpp test_common.hpp $(RTL_STAMP)
	@mkdir -p $(BUILD_DIR)
	$(VERILATOR) --cc --exe --build -j 0 -Wall -Wno-fatal -DSYNTHESIS \
		-I$(RTL_DIR) \
		--top-module $(TOP) \
		--Mdir $(OBJ_DIR) \
		-CFLAGS '$(CXXFLAGS)' \
		-o $(abspath $@) \
		$(RTL_DIR)/$(TOP).sv $(TEST_DIR)/main.cpp

clean:
	rm -rf $(BUILD_DIR) out
