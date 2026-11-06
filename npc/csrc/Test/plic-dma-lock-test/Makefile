SHELL := /bin/bash

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
NPC_DIR := $(abspath $(TEST_DIR)/../../..)
BUILD_DIR := $(TEST_DIR)/build
RTL_DIR := $(BUILD_DIR)/rtl
BIN_DIR := $(BUILD_DIR)/bin

VERILATOR ?= verilator
MILL ?= mill
CXXFLAGS := -std=c++20 -O2 -Wall -Wextra -I$(TEST_DIR)
VERILATOR_FLAGS := --cc --exe --build -j 0 -Wall -Wno-fatal -DSYNTHESIS -I$(TEST_DIR)

STAMP := $(RTL_DIR)/.generated
PLIC_RTL := $(RTL_DIR)/ysyx_26030103_PLIC.sv
BRIDGE_RTL := $(RTL_DIR)/ysyx_26030103_AXI5WidthBridge.sv
XBAR_RTL := $(RTL_DIR)/ysyx_26030103_AXI5Xbar.sv
ARBITER_RTL := $(RTL_DIR)/ysyx_26030103_AXI5Arbiter.sv

PLIC_BIN := $(BIN_DIR)/plic_claim_complete_race_test
BRIDGE_BIN := $(BIN_DIR)/width_bridge_lock_test
XBAR_BIN := $(BIN_DIR)/xbar_plic_pma_test
ARBITER_BIN := $(BIN_DIR)/arbiter_lock_reject_test
BINS := $(PLIC_BIN) $(BRIDGE_BIN) $(XBAR_BIN) $(ARBITER_BIN)

SCALA_SRCS := \
	$(TEST_DIR)/scala/PlicDmaLockHarness.scala \
	$(TEST_DIR)/scala/PlicConfigContractTest.scala \
	$(TEST_DIR)/build.mill \
	$(TEST_DIR)/Makefile \
	$(NPC_DIR)/src/Common/ysyx_26030103_PMA.scala \
	$(NPC_DIR)/src/config/ysyx_26030103_MMUProfile.scala \
	$(NPC_DIR)/src/Infra/ysyx_26030103_PLIC.scala \
	$(NPC_DIR)/src/Infra/AXI5/ysyx_26030103_AXIReadId.scala \
	$(NPC_DIR)/src/Infra/AXI5/ysyx_26030103_AXI5IO.scala \
	$(NPC_DIR)/src/Infra/AXI5/ysyx_26030103_AXI5AW.scala \
	$(NPC_DIR)/src/Infra/AXI5/ysyx_26030103_AXI5W.scala \
	$(NPC_DIR)/src/Infra/AXI5/ysyx_26030103_AXI5B.scala \
	$(NPC_DIR)/src/Infra/AXI5/ysyx_26030103_AXI5AR.scala \
	$(NPC_DIR)/src/Infra/AXI5/ysyx_26030103_AXI5R.scala \
	$(NPC_DIR)/src/Infra/AXI5/ysyx_26030103_AXI5Slave.scala \
	$(NPC_DIR)/src/Infra/AXI5/ysyx_26030103_AXI5Arbiter.scala \
	$(NPC_DIR)/src/Infra/AXI5/ysyx_26030103_AXI5WidthBridge.scala \
	$(NPC_DIR)/src/Infra/AXI5/ysyx_26030103_AXI5Xbar.scala

.PHONY: all test run clean

all: $(BINS)

test run: all
	@status=0; \
	for binary in $(BINS); do \
	  "$$binary" || status=1; \
	done; \
	cd $(TEST_DIR) && $(MILL) --no-server runMain \
	  ysyx_26030103.test.PlicConfigContractTest || status=1; \
	exit $$status

$(STAMP): $(SCALA_SRCS)
	@mkdir -p $(RTL_DIR)
	@cd $(TEST_DIR) && $(MILL) --no-server runMain \
	  ysyx_26030103.test.PlicClaimCompleteHarnessElaborate \
	  --target-dir $(RTL_DIR)
	@cd $(TEST_DIR) && $(MILL) --no-server runMain \
	  ysyx_26030103.test.WidthBridgeLockHarnessElaborate \
	  --target-dir $(RTL_DIR)
	@cd $(TEST_DIR) && $(MILL) --no-server runMain \
	  ysyx_26030103.test.XbarPlicPmaHarnessElaborate \
	  --target-dir $(RTL_DIR)
	@cd $(TEST_DIR) && $(MILL) --no-server runMain \
	  ysyx_26030103.test.ArbiterLockHarnessElaborate \
	  --target-dir $(RTL_DIR)
	@touch $@

$(PLIC_BIN): plic_claim_complete_race_test.cpp test_common.hpp $(STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) \
	  --top-module ysyx_26030103_PLIC \
	  --Mdir $(BUILD_DIR)/obj-plic \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(PLIC_RTL) $<

$(BRIDGE_BIN): width_bridge_lock_test.cpp test_common.hpp $(STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) \
	  --top-module ysyx_26030103_AXI5WidthBridge \
	  --Mdir $(BUILD_DIR)/obj-bridge-lock \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(BRIDGE_RTL) $<

$(XBAR_BIN): xbar_plic_pma_test.cpp test_common.hpp $(STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) \
	  --top-module ysyx_26030103_AXI5Xbar \
	  --Mdir $(BUILD_DIR)/obj-xbar-plic-pma \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(XBAR_RTL) $<

$(ARBITER_BIN): arbiter_lock_reject_test.cpp test_common.hpp $(STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) \
	  --top-module ysyx_26030103_AXI5Arbiter \
	  --Mdir $(BUILD_DIR)/obj-arbiter-lock \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(ARBITER_RTL) $<

clean:
	rm -rf $(BUILD_DIR) out
