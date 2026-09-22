SHELL := /bin/bash

NPC_DIR := $(abspath ../../..)
BUILD_DIR := $(CURDIR)/build
RTL_DIR := $(BUILD_DIR)/rtl
BIN_DIR := $(BUILD_DIR)/bin

VERILATOR ?= verilator
CXXFLAGS := -std=c++20 -O2 -Wall -Wextra -I$(CURDIR)
VERILATOR_FLAGS := --cc --exe --build -j 0 -Wall -Wno-fatal -DSYNTHESIS -I$(RTL_DIR)

SCALA_SOURCES := $(shell find $(NPC_DIR)/src -type f -name '*.scala' | sort)
GEN_STAMP := $(RTL_DIR)/.generated

ARBITER_TOP := ysyx_26030103_AXI5Arbiter
XBAR_TOP := ysyx_26030103_AXI5Xbar
DCACHE_TOP := ysyx_26030103_DCache
LSU_TOP := ysyx_26030103_LSU
CLINT_TOP := ysyx_26030103_AXI5CLINTSlave
AXIRAM_TOP := riscv32e_npc_AXIRAM

ARBITER_BIN := $(BIN_DIR)/arbiter_test
XBAR_BIN := $(BIN_DIR)/xbar_test
DCACHE_BIN := $(BIN_DIR)/dcache_test
LSU_BIN := $(BIN_DIR)/lsu_store_test
CLINT_BIN := $(BIN_DIR)/clint_test
AXIRAM_BIN := $(BIN_DIR)/axiram_test
BINS := $(ARBITER_BIN) $(XBAR_BIN) $(DCACHE_BIN) $(LSU_BIN) $(CLINT_BIN) $(AXIRAM_BIN)

.PHONY: all test run clean

all: $(BINS)

test run: all
	@$(ARBITER_BIN)
	@$(XBAR_BIN)
	@$(DCACHE_BIN)
	@$(LSU_BIN)
	@$(CLINT_BIN)
	@$(AXIRAM_BIN)

# Call the production elaborator directly.  Do not use `make verilog`: that
# repository target contains an unrelated git-commit hook.
$(GEN_STAMP): $(SCALA_SOURCES)
	@mkdir -p $(RTL_DIR)
	@cd $(NPC_DIR) && env \
	  CACHE_BLOCK_SIZE_LOG2=4 CACHE_INDEX_BITS=5 \
	  ICACHE=y DCACHE=y WBUF_DEPTH=4 CACHE_PADDING=0 \
	  BTB_BITS=4 BTB_WAYS=1 JAL_BTB_BITS=4 JAL_BTB_WAYS=1 RAS_BITS=4 \
	  RV32_M=n RV32_A=n RV32_C=n \
	  mill --no-server runMain ysyx_26030103_Elaborate --target-dir $(RTL_DIR)
	@ln -sf layers-ysyx_26030103-Verification.sv \
	  $(RTL_DIR)/layers-riscv32e_npc_AXIRAM-Verification.sv
	@touch $@

$(ARBITER_BIN): arbiter_test.cpp test_common.hpp $(GEN_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) \
	  --top-module $(ARBITER_TOP) \
	  --Mdir $(BUILD_DIR)/obj-arbiter \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(RTL_DIR)/ysyx_26030103.sv arbiter_test.cpp

$(XBAR_BIN): xbar_test.cpp test_common.hpp $(GEN_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) \
	  --top-module $(XBAR_TOP) \
	  --Mdir $(BUILD_DIR)/obj-xbar \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(RTL_DIR)/ysyx_26030103.sv xbar_test.cpp

$(DCACHE_BIN): dcache_test.cpp test_common.hpp $(GEN_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) \
	  --top-module $(DCACHE_TOP) \
	  --Mdir $(BUILD_DIR)/obj-dcache \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(RTL_DIR)/ysyx_26030103.sv dcache_test.cpp

$(LSU_BIN): lsu_store_test.cpp test_common.hpp $(GEN_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) \
	  --top-module $(LSU_TOP) \
	  --Mdir $(BUILD_DIR)/obj-lsu \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(RTL_DIR)/ysyx_26030103.sv lsu_store_test.cpp

$(CLINT_BIN): clint_test.cpp test_common.hpp $(GEN_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) \
	  --top-module $(CLINT_TOP) \
	  --Mdir $(BUILD_DIR)/obj-clint \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(RTL_DIR)/ysyx_26030103.sv clint_test.cpp

$(AXIRAM_BIN): axiram_test.cpp test_common.hpp $(GEN_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) \
	  --top-module $(AXIRAM_TOP) \
	  -I$(RTL_DIR) \
	  --Mdir $(BUILD_DIR)/obj-axiram \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(RTL_DIR)/$(AXIRAM_TOP).sv axiram_test.cpp

clean:
	rm -rf $(BUILD_DIR)
