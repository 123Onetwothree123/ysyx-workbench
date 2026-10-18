SHELL := /bin/bash

NPC_DIR := $(abspath ../../..)
BUILD_DIR := $(CURDIR)/build
RTL_DIR := $(BUILD_DIR)/rtl
CHIPLINK_RTL_DIR := $(BUILD_DIR)/rtl-chiplink
ICACHE_DISABLED_RTL_DIR := $(BUILD_DIR)/rtl-icache-disabled
BIN_DIR := $(BUILD_DIR)/bin

VERILATOR ?= verilator
CXXFLAGS := -std=c++20 -O2 -Wall -Wextra -I$(CURDIR)
VERILATOR_FLAGS := --cc --exe --build -j 0 -Wall -Wno-fatal -DSYNTHESIS -I$(RTL_DIR)

SCALA_SOURCES := $(shell find $(NPC_DIR)/src -type f -name '*.scala' | sort)
GEN_STAMP := $(RTL_DIR)/.generated
CHIPLINK_GEN_STAMP := $(CHIPLINK_RTL_DIR)/.generated
ICACHE_DISABLED_GEN_STAMP := $(ICACHE_DISABLED_RTL_DIR)/.generated
ICACHE_DISABLED_LOG := $(ICACHE_DISABLED_RTL_DIR)/elaboration.log

ARBITER_TOP := ysyx_26030103_AXI5Arbiter
XBAR_TOP := ysyx_26030103_AXI5Xbar
ICACHE_TOP := ysyx_26030103_ICacheReg
DCACHE_TOP := ysyx_26030103_DCacheReg
DMA_ERROR_SLAVE_TOP := ysyx_26030103_AXI5DMAErrorSlave
IDU_TOP := ysyx_26030103_IDU
PREDICTOR_TOP := ysyx_26030103_PredictorSelect
BTB_TOP := ysyx_26030103_BTB
RAS_TOP := ysyx_26030103_RAS
LSU_TOP := ysyx_26030103_LSU
CLINT_TOP := ysyx_26030103_AXI5CLINTSlave
AXIRAM_TOP := riscv32e_npc_AXIRAM

ARBITER_BIN := $(BIN_DIR)/arbiter_test
XBAR_BIN := $(BIN_DIR)/xbar_test
XBAR_CHIPLINK_BIN := $(BIN_DIR)/xbar_chiplink_test
ICACHE_BIN := $(BIN_DIR)/icache_pma_test
ICACHE_NPC_BIN := $(BIN_DIR)/icache_pma_npc_test
ICACHE_CHIPLINK_BIN := $(BIN_DIR)/icache_pma_chiplink_test
ICACHE_DISABLED_SOC_BIN := $(BIN_DIR)/icache_disabled_soc_test
ICACHE_DISABLED_NPC_BIN := $(BIN_DIR)/icache_disabled_npc_test
DCACHE_BIN := $(BIN_DIR)/dcache_test
DMA_ERROR_SLAVE_BIN := $(BIN_DIR)/dma_error_slave_test
DMA_TOP_CHIPLINK_BIN := $(BIN_DIR)/dma_top_chiplink_test
DMA_TOP_NO_CHIPLINK_BIN := $(BIN_DIR)/dma_top_no_chiplink_test
IDU_BIN := $(BIN_DIR)/idu_fence_test
PREDICTOR_BIN := $(BIN_DIR)/predictor_test
BTB_BIN := $(BIN_DIR)/btb_flush_test
RAS_BIN := $(BIN_DIR)/ras_flush_test
LSU_BIN := $(BIN_DIR)/lsu_store_test
CLINT_BIN := $(BIN_DIR)/clint_test
AXIRAM_BIN := $(BIN_DIR)/axiram_test
BINS := $(ARBITER_BIN) $(XBAR_BIN) $(XBAR_CHIPLINK_BIN) $(ICACHE_BIN) $(ICACHE_NPC_BIN) $(ICACHE_CHIPLINK_BIN) $(ICACHE_DISABLED_SOC_BIN) $(ICACHE_DISABLED_NPC_BIN) $(DCACHE_BIN) $(DMA_ERROR_SLAVE_BIN) $(DMA_TOP_CHIPLINK_BIN) $(DMA_TOP_NO_CHIPLINK_BIN) $(IDU_BIN) $(PREDICTOR_BIN) $(BTB_BIN) $(RAS_BIN) $(LSU_BIN) $(CLINT_BIN) $(AXIRAM_BIN)

.PHONY: all test run clean

all: $(BINS)

test run: all
	@$(ARBITER_BIN)
	@$(XBAR_BIN)
	@$(XBAR_CHIPLINK_BIN)
	@$(ICACHE_BIN)
	@$(ICACHE_NPC_BIN)
	@$(ICACHE_CHIPLINK_BIN)
	@$(ICACHE_DISABLED_SOC_BIN)
	@$(ICACHE_DISABLED_NPC_BIN)
	@$(DCACHE_BIN)
	@$(DMA_ERROR_SLAVE_BIN)
	@$(DMA_TOP_CHIPLINK_BIN)
	@$(DMA_TOP_NO_CHIPLINK_BIN)
	@$(IDU_BIN)
	@$(PREDICTOR_BIN)
	@$(BTB_BIN)
	@$(RAS_BIN)
	@$(LSU_BIN)
	@$(CLINT_BIN)
	@$(AXIRAM_BIN)

# Call the production elaborator directly.  Do not use `make verilog`: that
# repository target contains an unrelated git-commit hook.
$(GEN_STAMP): $(SCALA_SOURCES) Makefile
	@mkdir -p $(RTL_DIR)
	@cd $(NPC_DIR) && env \
	  CACHE_BLOCK_SIZE_LOG2=4 CACHE_INDEX_BITS=5 \
	  ICACHE=y DCACHE=y WBUF_DEPTH=4 CACHE_PADDING=0 \
	  CHIPLINK=n \
	  BTB_BITS=4 BTB_WAYS=1 JAL_BTB_BITS=4 JAL_BTB_WAYS=1 RAS_BITS=4 \
	  RV32_M=n RV32_A=n RV32_C=n \
	  SDB=y DIFFTEST=y MTRACE=y ITRACE=y FTRACE=y IRINGBUF=y PERF_STATS=y \
	  mill --no-server runMain ysyx_26030103_Elaborate --target-dir $(RTL_DIR)
	@ln -sf layers-ysyx_26030103-Verification.sv \
	  $(RTL_DIR)/layers-riscv32e_npc_AXIRAM-Verification.sv
	@if ! grep -F 'ysyx_26030103_AXI5DMAErrorSlave dmaErrorSlave' \
	    $(RTL_DIR)/ysyx_26030103.sv >/dev/null; then \
	  echo "CHIPLINK=n top no longer terminates io_slave with DMAErrorSlave" >&2; \
	  exit 1; \
	fi
	@if grep -E '^module ysyx_26030103_(MDU|MUL|DIV)' \
	    $(RTL_DIR)/ysyx_26030103.sv $(RTL_DIR)/ysyx_26030103_npc.sv; then \
	  echo "RV32_M=n still generated an MDU/MUL/DIV module" >&2; \
	  exit 1; \
	fi
	@if grep -E 'MDUInstQueue|MDUQueue(Head|Tail|Count)' \
	    $(RTL_DIR)/ysyx_26030103.sv $(RTL_DIR)/ysyx_26030103_npc.sv; then \
	  echo "RV32_M=n still generated EXU MDU queue state" >&2; \
	  exit 1; \
	fi
	@touch $@

$(CHIPLINK_GEN_STAMP): $(SCALA_SOURCES) Makefile
	@mkdir -p $(CHIPLINK_RTL_DIR)
	@cd $(NPC_DIR) && env \
	  CACHE_BLOCK_SIZE_LOG2=4 CACHE_INDEX_BITS=5 \
	  ICACHE=y DCACHE=y WBUF_DEPTH=4 CACHE_PADDING=0 \
	  CHIPLINK=y \
	  BTB_BITS=4 BTB_WAYS=1 JAL_BTB_BITS=4 JAL_BTB_WAYS=1 RAS_BITS=4 \
	  RV32_M=n RV32_A=n RV32_C=n \
	  SDB=y DIFFTEST=y MTRACE=y ITRACE=y FTRACE=y IRINGBUF=y PERF_STATS=y \
	  mill --no-server runMain ysyx_26030103_Elaborate --target-dir $(CHIPLINK_RTL_DIR)
	@ln -sf layers-ysyx_26030103-Verification.sv \
	  $(CHIPLINK_RTL_DIR)/layers-riscv32e_npc_AXIRAM-Verification.sv
	@if ! grep -F "io_master_bid == 4'h2" \
	    $(CHIPLINK_RTL_DIR)/ysyx_26030103.sv >/dev/null; then \
	  echo "ChipLink RTL top-level BID assertion does not permit DMA ID 2" >&2; \
	  exit 1; \
	fi
	@if ! grep -F "io_master_rid == 4'h2" \
	    $(CHIPLINK_RTL_DIR)/ysyx_26030103.sv >/dev/null; then \
	  echo "ChipLink RTL top-level RID assertion does not permit DMA ID 2" >&2; \
	  exit 1; \
	fi
	@if ! grep -E '\.io_dma_AR_ARID[[:space:]]+\(io_slave_arid\)' \
	    $(CHIPLINK_RTL_DIR)/ysyx_26030103.sv >/dev/null; then \
	  echo "CHIPLINK=y top does not connect io_slave AR to the DMA arbiter" >&2; \
	  exit 1; \
	fi
	@if ! grep -E '\.io_DCacheFlush[[:space:]]+\(_arbiter_io_DMAWriteInvalidate\)' \
	    $(CHIPLINK_RTL_DIR)/ysyx_26030103.sv >/dev/null; then \
	  echo "CHIPLINK=y top does not connect DMA writes to DCache invalidation" >&2; \
	  exit 1; \
	fi
	@touch $@

# ICache关闭时生产elaborator仍同时生成direct-NPC和SoC。保留完整日志，
# 防止单项占位Vec再次被宽动态索引访问而恢复W004告警。
$(ICACHE_DISABLED_GEN_STAMP): $(SCALA_SOURCES) Makefile
	@mkdir -p $(ICACHE_DISABLED_RTL_DIR)
	@set -o pipefail; cd $(NPC_DIR) && env \
	  CACHE_BLOCK_SIZE_LOG2=4 CACHE_INDEX_BITS=5 \
	  ICACHE=n DCACHE=n WBUF_DEPTH=4 CACHE_PADDING=0 \
	  CHIPLINK=n \
	  BTB_BITS=4 BTB_WAYS=1 JAL_BTB_BITS=4 JAL_BTB_WAYS=1 RAS_BITS=4 \
	  RV32_M=n RV32_A=n RV32_C=n \
	  SDB=y DIFFTEST=y MTRACE=y ITRACE=y FTRACE=y IRINGBUF=y PERF_STATS=y \
	  mill --no-server runMain ysyx_26030103_Elaborate \
	    --target-dir $(ICACHE_DISABLED_RTL_DIR) 2>&1 | tee $(ICACHE_DISABLED_LOG)
	@if grep -E '\[W004\]|Dynamic index.*Vec of size 1' \
	    $(ICACHE_DISABLED_LOG); then \
	  echo "ICACHE=n still emits a wide Vec index warning" >&2; \
	  exit 1; \
	fi
	@test -s $(ICACHE_DISABLED_RTL_DIR)/ysyx_26030103.sv
	@test -s $(ICACHE_DISABLED_RTL_DIR)/ysyx_26030103_npc.sv
	@touch $@

$(ARBITER_BIN): arbiter_test.cpp test_common.hpp $(CHIPLINK_GEN_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) --cc --exe --build -j 0 -Wall -Wno-fatal -DSYNTHESIS \
	  -I$(CHIPLINK_RTL_DIR) \
	  --top-module $(ARBITER_TOP) \
	  --Mdir $(BUILD_DIR)/obj-arbiter \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(CHIPLINK_RTL_DIR)/ysyx_26030103.sv arbiter_test.cpp

$(XBAR_BIN): xbar_test.cpp test_common.hpp $(GEN_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) \
	  --top-module $(XBAR_TOP) \
	  --Mdir $(BUILD_DIR)/obj-xbar \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(RTL_DIR)/ysyx_26030103.sv xbar_test.cpp

$(XBAR_CHIPLINK_BIN): xbar_test.cpp test_common.hpp $(CHIPLINK_GEN_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) --cc --exe --build -j 0 -Wall -Wno-fatal -DSYNTHESIS \
	  -I$(CHIPLINK_RTL_DIR) \
	  --top-module $(XBAR_TOP) \
	  --Mdir $(BUILD_DIR)/obj-xbar-chiplink \
	  -CFLAGS '$(CXXFLAGS) -DTEST_CHIPLINK' \
	  -o $(abspath $@) \
	  $(CHIPLINK_RTL_DIR)/ysyx_26030103.sv xbar_test.cpp

$(ICACHE_BIN): icache_pma_test.cpp test_common.hpp $(GEN_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) \
	  --top-module $(ICACHE_TOP) \
	  --Mdir $(BUILD_DIR)/obj-icache-pma \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(RTL_DIR)/ysyx_26030103.sv icache_pma_test.cpp

$(ICACHE_NPC_BIN): icache_pma_test.cpp test_common.hpp $(GEN_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) \
	  --top-module $(ICACHE_TOP) \
	  --Mdir $(BUILD_DIR)/obj-icache-pma-npc \
	  -CFLAGS '$(CXXFLAGS) -DTEST_NPC' \
	  -o $(abspath $@) \
	  $(RTL_DIR)/ysyx_26030103_npc.sv icache_pma_test.cpp

$(ICACHE_CHIPLINK_BIN): icache_pma_test.cpp test_common.hpp $(CHIPLINK_GEN_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) --cc --exe --build -j 0 -Wall -Wno-fatal -DSYNTHESIS \
	  -I$(CHIPLINK_RTL_DIR) \
	  --top-module $(ICACHE_TOP) \
	  --Mdir $(BUILD_DIR)/obj-icache-pma-chiplink \
	  -CFLAGS '$(CXXFLAGS) -DTEST_CHIPLINK' \
	  -o $(abspath $@) \
	  $(CHIPLINK_RTL_DIR)/ysyx_26030103.sv icache_pma_test.cpp

$(ICACHE_DISABLED_SOC_BIN): icache_pma_test.cpp test_common.hpp $(ICACHE_DISABLED_GEN_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) --cc --exe --build -j 0 -Wall -Wno-fatal -DSYNTHESIS \
	  -I$(ICACHE_DISABLED_RTL_DIR) \
	  --top-module $(ICACHE_TOP) \
	  --Mdir $(BUILD_DIR)/obj-icache-disabled-soc \
	  -CFLAGS '$(CXXFLAGS) -DTEST_CACHE_DISABLED' \
	  -o $(abspath $@) \
	  $(ICACHE_DISABLED_RTL_DIR)/ysyx_26030103.sv icache_pma_test.cpp

$(ICACHE_DISABLED_NPC_BIN): icache_pma_test.cpp test_common.hpp $(ICACHE_DISABLED_GEN_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) --cc --exe --build -j 0 -Wall -Wno-fatal -DSYNTHESIS \
	  -I$(ICACHE_DISABLED_RTL_DIR) \
	  --top-module $(ICACHE_TOP) \
	  --Mdir $(BUILD_DIR)/obj-icache-disabled-npc \
	  -CFLAGS '$(CXXFLAGS) -DTEST_CACHE_DISABLED -DTEST_NPC' \
	  -o $(abspath $@) \
	  $(ICACHE_DISABLED_RTL_DIR)/ysyx_26030103_npc.sv icache_pma_test.cpp

$(DCACHE_BIN): dcache_test.cpp test_common.hpp $(GEN_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) \
	  --top-module $(DCACHE_TOP) \
	  --Mdir $(BUILD_DIR)/obj-dcache \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(RTL_DIR)/ysyx_26030103.sv dcache_test.cpp

$(DMA_ERROR_SLAVE_BIN): dma_error_slave_test.cpp test_common.hpp $(GEN_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) \
	  --top-module $(DMA_ERROR_SLAVE_TOP) \
	  --Mdir $(BUILD_DIR)/obj-dma-error-slave \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(RTL_DIR)/ysyx_26030103.sv dma_error_slave_test.cpp

$(DMA_TOP_CHIPLINK_BIN): dma_top_test.cpp test_common.hpp $(CHIPLINK_GEN_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) --cc --exe --build -j 0 -Wall -Wno-fatal -DSYNTHESIS \
	  -I$(CHIPLINK_RTL_DIR) \
	  --top-module ysyx_26030103 \
	  --Mdir $(BUILD_DIR)/obj-dma-top-chiplink \
	  -CFLAGS '$(CXXFLAGS) -DTEST_CHIPLINK' \
	  -o $(abspath $@) \
	  $(CHIPLINK_RTL_DIR)/ysyx_26030103.sv dma_top_test.cpp

$(DMA_TOP_NO_CHIPLINK_BIN): dma_top_test.cpp test_common.hpp $(GEN_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) --cc --exe --build -j 0 -Wall -Wno-fatal -DSYNTHESIS \
	  -I$(RTL_DIR) \
	  --top-module ysyx_26030103 \
	  --Mdir $(BUILD_DIR)/obj-dma-top-no-chiplink \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(RTL_DIR)/ysyx_26030103.sv dma_top_test.cpp

$(IDU_BIN): idu_fence_test.cpp test_common.hpp $(GEN_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) \
	  --top-module $(IDU_TOP) \
	  --Mdir $(BUILD_DIR)/obj-idu \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(RTL_DIR)/ysyx_26030103.sv idu_fence_test.cpp

$(PREDICTOR_BIN): predictor_test.cpp test_common.hpp $(GEN_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) \
	  --top-module $(PREDICTOR_TOP) \
	  --Mdir $(BUILD_DIR)/obj-predictor \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(RTL_DIR)/ysyx_26030103.sv predictor_test.cpp

$(BTB_BIN): btb_flush_test.cpp test_common.hpp $(GEN_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) \
	  --top-module $(BTB_TOP) \
	  --Mdir $(BUILD_DIR)/obj-btb \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(RTL_DIR)/ysyx_26030103.sv btb_flush_test.cpp

$(RAS_BIN): ras_flush_test.cpp test_common.hpp $(GEN_STAMP)
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) \
	  --top-module $(RAS_TOP) \
	  --Mdir $(BUILD_DIR)/obj-ras \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(RTL_DIR)/ysyx_26030103.sv ras_flush_test.cpp

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
