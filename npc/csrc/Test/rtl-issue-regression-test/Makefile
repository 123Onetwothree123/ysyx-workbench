SHELL := /bin/bash

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
NPC_DIR := $(abspath $(TEST_DIR)/../../..)
BUILD_DIR := $(TEST_DIR)/build
RTL_DIR := $(BUILD_DIR)/rtl
HARNESS_RTL_DIR := $(BUILD_DIR)/stage-connect-rtl
BIN_DIR := $(BUILD_DIR)/bin

VERILATOR ?= verilator
MILL ?= mill
CXXFLAGS := -std=c++20 -O2 -Wall -Wextra -I$(TEST_DIR)
VERILATOR_FLAGS := --cc --exe --build -j 0 -Wall -Wno-fatal -DSYNTHESIS -I$(RTL_DIR)

SCALA_SOURCES := $(shell find $(NPC_DIR)/src -type f -name '*.scala' | sort)
LOCAL_SCALA_SOURCES := $(shell find $(TEST_DIR)/scala -type f -name '*.scala' | sort)
GEN_STAMP := $(RTL_DIR)/.generated
HARNESS_STAMP := $(HARNESS_RTL_DIR)/.generated

XBAR_BIN := $(BIN_DIR)/xbar_local_decerr_interleave_test
EXU_BIN := $(BIN_DIR)/exu_mdu_ordering_test
MUL_CORE_BIN := $(BIN_DIR)/mul_core_pipeline_test
LSU_BIN := $(BIN_DIR)/lsu_bad_bid_test
STAGE_CONNECT_BIN := $(BIN_DIR)/stage_connect_flush_test
ICACHE_PMA_BIN := $(BIN_DIR)/icache_pma_boundary_test
ZERO_PREDICTOR_BIN := $(BIN_DIR)/zero_predictor_geometry_test
BINS := $(XBAR_BIN) $(MUL_CORE_BIN) $(EXU_BIN) $(LSU_BIN) $(STAGE_CONNECT_BIN) $(ICACHE_PMA_BIN) $(ZERO_PREDICTOR_BIN)

.PHONY: all test run functional-test config-test xbar-test mul-core-test exu-test lsu-test stage-connect-test icache-pma-test zero-predictor-test clean

all: $(BINS)

# Run every independent probe even when an earlier one fails.  These are
# correctness regressions: a currently reproduced RTL defect intentionally
# makes this target return nonzero until that defect is fixed.
test run: all
	@status=0; \
	for binary in $(BINS); do \
	  "$$binary" || status=1; \
	done; \
	cd $(TEST_DIR) && $(MILL) --no-server runMain ysyx_26030103.test.ConfigGuardTest || status=1; \
	exit $$status

# Focused gate for the production RTL fixes.  Broader `make test` intentionally
# retains the independent ICache custom-PMA and configuration-guard findings as
# future work.
functional-test: $(XBAR_BIN) $(MUL_CORE_BIN) $(EXU_BIN) $(LSU_BIN) $(STAGE_CONNECT_BIN)
	$(XBAR_BIN)
	$(MUL_CORE_BIN)
	$(EXU_BIN)
	$(LSU_BIN)
	$(STAGE_CONNECT_BIN)

config-test:
	cd $(TEST_DIR) && $(MILL) --no-server runMain ysyx_26030103.test.ConfigGuardTest

xbar-test: $(XBAR_BIN)
	$(XBAR_BIN)

exu-test: $(EXU_BIN)
	$(EXU_BIN)

mul-core-test: $(MUL_CORE_BIN)
	$(MUL_CORE_BIN)

lsu-test: $(LSU_BIN)
	$(LSU_BIN)

stage-connect-test: $(STAGE_CONNECT_BIN)
	$(STAGE_CONNECT_BIN)

icache-pma-test: $(ICACHE_PMA_BIN)
	$(ICACHE_PMA_BIN)

zero-predictor-test: $(ZERO_PREDICTOR_BIN)
	$(ZERO_PREDICTOR_BIN)

# Generate one production snapshot with a genuinely pipelined compression-tree
# core.  The leaf EXU, LSU and Xbar modules are then selected as independent
# Verilator tops by the tests below.
$(GEN_STAMP): $(SCALA_SOURCES) Makefile
	@mkdir -p $(RTL_DIR)
	@cd $(NPC_DIR) && env \
	  CACHE_BLOCK_SIZE_LOG2=4 CACHE_INDEX_BITS=5 \
	  ICACHE=y DCACHE=y WBUF_DEPTH=4 CACHE_PADDING=0 CHIPLINK=n \
	  BTB_BITS=4 BTB_WAYS=1 JAL_BTB_BITS=4 JAL_BTB_WAYS=1 RAS_BITS=4 \
	  RV32_M=y RV32_A=n RV32_C=n \
	  MUL_IMPL=wallace MUL_ENCODING=plain MUL_RADIX=4 MUL_WIDTH=32 \
	  MUL_COMPRESSOR_INPUTS=3 MUL_COMPRESSOR_OUTPUTS=2 \
	  MUL_COMPRESSOR_OUTPUT_OFFSETS=0,1 \
	  MUL_COMPRESSOR_HAS_CIN=n MUL_COMPRESSOR_HAS_COUT=n \
	  MUL_COMPRESSOR_COUT_OFFSET=1 MUL_COMPRESSOR_ALLOW_REDUNDANT=n \
	  MUL_ITER_BITS=1 MUL_PIPELINE=2 MUL_SPLIT=1 MUL_EARLY_OUT=n \
	  DIV_IMPL=restoring DIV_RADIX=2 DIV_ITER_BITS=1 DIV_EARLY_OUT=n \
	  $(MILL) --no-server runMain ysyx_26030103_Elaborate --target-dir $(RTL_DIR)
	@touch $@

$(HARNESS_STAMP): $(LOCAL_SCALA_SOURCES) build.mill \
                    layers-StageConnectHarness-Verification.sv \
                    layers-ICachePmaBoundaryHarness-Verification.sv \
                    layers-ZeroPredictorGeometryHarness-Verification.sv \
                    $(SCALA_SOURCES)
	@mkdir -p $(HARNESS_RTL_DIR)
	@cd $(TEST_DIR) && $(MILL) --no-server runMain \
	  ysyx_26030103.test.StageConnectHarnessElaborate \
	  --target-dir $(HARNESS_RTL_DIR)
	@cd $(TEST_DIR) && $(MILL) --no-server runMain \
	  ysyx_26030103.test.ICachePmaBoundaryHarnessElaborate \
	  --target-dir $(HARNESS_RTL_DIR)
	@cd $(TEST_DIR) && $(MILL) --no-server runMain \
	  ysyx_26030103.test.ZeroPredictorGeometryHarnessElaborate \
	  --target-dir $(HARNESS_RTL_DIR)
	@cp $(TEST_DIR)/layers-StageConnectHarness-Verification.sv $(HARNESS_RTL_DIR)/
	@cp $(TEST_DIR)/layers-ICachePmaBoundaryHarness-Verification.sv $(HARNESS_RTL_DIR)/
	@cp $(TEST_DIR)/layers-ZeroPredictorGeometryHarness-Verification.sv $(HARNESS_RTL_DIR)/
	@touch $@

$(XBAR_BIN): xbar_local_decerr_interleave_test.cpp test_common.hpp $(GEN_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) \
	  --top-module ysyx_26030103_AXI5Xbar \
	  --Mdir $(BUILD_DIR)/obj-xbar-local-decerr \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(RTL_DIR)/ysyx_26030103.sv $<

$(EXU_BIN): exu_mdu_ordering_test.cpp test_common.hpp $(GEN_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) \
	  --top-module ysyx_26030103_EXU \
	  --Mdir $(BUILD_DIR)/obj-exu-mdu-ordering \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(RTL_DIR)/ysyx_26030103.sv $<

$(MUL_CORE_BIN): mul_core_pipeline_test.cpp test_common.hpp $(GEN_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) \
	  --top-module ysyx_26030103_MULWallaceCore \
	  --Mdir $(BUILD_DIR)/obj-mul-core-pipeline \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(RTL_DIR)/ysyx_26030103.sv $<

$(LSU_BIN): lsu_bad_bid_test.cpp test_common.hpp $(GEN_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) \
	  --top-module ysyx_26030103_LSU \
	  --Mdir $(BUILD_DIR)/obj-lsu-bad-bid \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(RTL_DIR)/ysyx_26030103.sv $<

$(STAGE_CONNECT_BIN): stage_connect_flush_test.cpp test_common.hpp $(HARNESS_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) --cc --exe --build -j 0 -Wall -Wno-fatal -DSYNTHESIS \
	  -I$(HARNESS_RTL_DIR) \
	  --top-module StageConnectHarness \
	  --Mdir $(BUILD_DIR)/obj-stage-connect \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(HARNESS_RTL_DIR)/StageConnectHarness.sv $<

$(ICACHE_PMA_BIN): icache_pma_boundary_test.cpp test_common.hpp $(HARNESS_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) --cc --exe --build -j 0 -Wall -Wno-fatal -DSYNTHESIS \
	  -I$(HARNESS_RTL_DIR) \
	  --top-module ICachePmaBoundaryHarness \
	  --Mdir $(BUILD_DIR)/obj-icache-pma-boundary \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(HARNESS_RTL_DIR)/ICachePmaBoundaryHarness.sv $<

$(ZERO_PREDICTOR_BIN): zero_predictor_geometry_test.cpp test_common.hpp $(HARNESS_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) --cc --exe --build -j 0 -Wall -Wno-fatal -DSYNTHESIS \
	  -I$(HARNESS_RTL_DIR) \
	  --top-module ZeroPredictorGeometryHarness \
	  --Mdir $(BUILD_DIR)/obj-zero-predictor \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(HARNESS_RTL_DIR)/ZeroPredictorGeometryHarness.sv $<

clean:
	rm -rf $(BUILD_DIR) out
