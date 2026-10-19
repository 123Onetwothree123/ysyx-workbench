SHELL := /bin/bash

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
NPC_DIR := $(abspath $(TEST_DIR)/../../..)
BUILD_DIR := $(TEST_DIR)/build
BASE_RTL := $(BUILD_DIR)/rtl-base
SRAM_RTL := $(BUILD_DIR)/rtl-sram
NOBYPASS_RTL := $(BUILD_DIR)/rtl-nobypass
SMALLLINE_RTL := $(BUILD_DIR)/rtl-smallline
GPC32_RTL := $(BUILD_DIR)/rtl-gpc32
GPC43_RTL := $(BUILD_DIR)/rtl-gpc43
BTB2_RTL := $(BUILD_DIR)/rtl-btb2
MDUCFG_RTL := $(BUILD_DIR)/rtl-mdu-cfg
BIN_DIR := $(BUILD_DIR)/bin

VERILATOR ?= verilator
CXXFLAGS := -std=c++20 -O2 -Wall -Wextra -I$(TEST_DIR)
VFLAGS := --cc --exe --build -j 0 -Wall -Wno-fatal -DSYNTHESIS

IDU_BIN := $(BIN_DIR)/idu_perf_counter_test
IRQ_BIN := $(BIN_DIR)/interrupt_pulse_test
DCACHE_REG_BIN := $(BIN_DIR)/dcache_slot_reg_test
DCACHE_SRAM_BIN := $(BIN_DIR)/dcache_slot_sram_test
DCACHE_NOBYPASS_BIN := $(BIN_DIR)/dcache_slot_nobypass_test
ICACHE_BIN := $(BIN_DIR)/icache_outstanding_test
ICACHE_SMALL_BIN := $(BIN_DIR)/icache_smallline_test
MUL_DEFAULT_BIN := $(BIN_DIR)/mul_gpc_default_test
MUL_ALT_BIN := $(BIN_DIR)/mul_gpc_alt_test
MCYCLE_BIN := $(BIN_DIR)/mcycle_semantics_test
AXIRAM_BIN := $(BIN_DIR)/axiram_window_test
BTB2_BIN := $(BIN_DIR)/btb_twoway_test
MULCFG_BIN := $(BIN_DIR)/mul_config_core_test
MDU_BLOCK_BIN := $(BIN_DIR)/mdu_pipeline_block_test
MDU_BLOCK_FAST_BIN := $(BIN_DIR)/mdu_pipeline_block_fast_test
FULLCORE_BIN := $(BIN_DIR)/fullcore_stall_test
PERFSEM_BIN := $(BIN_DIR)/perf_counter_semantics_test
PERFSEM_MIXED_BIN := $(BIN_DIR)/perf_counter_semantics_mixed_test
XBAR_HOL_BIN := $(BIN_DIR)/xbar_r_hol_test
CHIPLINK_RTL := $(BUILD_DIR)/rtl-chiplink
PROD_RTL := $(BUILD_DIR)/rtl-prod
MIXED_RTL := $(BUILD_DIR)/rtl-mixed
DMA_WINDOW_BIN := $(BIN_DIR)/dma_coherence_window_test
ICACHE_SRAM_BIN := $(BIN_DIR)/icache_sram_test
DIVCFG_BIN := $(BIN_DIR)/div_config_core_test
DCACHE_REFILL_REG_BIN := $(BIN_DIR)/dcache_refill_reg_test
DCACHE_REFILL_SRAM_BIN := $(BIN_DIR)/dcache_refill_sram_test
DCACHE_REFILL_NOBYPASS_BIN := $(BIN_DIR)/dcache_refill_nobypass_test
BINS := $(IDU_BIN) $(IRQ_BIN) $(DCACHE_REG_BIN) $(DCACHE_SRAM_BIN) \
        $(DCACHE_NOBYPASS_BIN) $(ICACHE_BIN) $(ICACHE_SMALL_BIN) \
        $(MUL_DEFAULT_BIN) $(MUL_ALT_BIN) $(MCYCLE_BIN) $(AXIRAM_BIN) $(BTB2_BIN) $(DCACHE_REFILL_REG_BIN) $(DCACHE_REFILL_SRAM_BIN) $(DCACHE_REFILL_NOBYPASS_BIN) $(MULCFG_BIN) $(DIVCFG_BIN) $(MDU_BLOCK_BIN) $(MDU_BLOCK_FAST_BIN) $(FULLCORE_BIN) $(PERFSEM_BIN) $(PERFSEM_MIXED_BIN) $(XBAR_HOL_BIN) $(DCACHE_SMALL_BIN) $(DMA_WINDOW_BIN) $(ICACHE_SRAM_BIN)

.PHONY: all test run rtl-check clean

all: $(BINS)

# 先运行 ./elaborate-all.sh 与 ./elaborate-sram.sh 生成 RTL 快照。
rtl-check:
	@for dir in $(BASE_RTL) $(SRAM_RTL) $(NOBYPASS_RTL) $(SMALLLINE_RTL) \
	            $(GPC32_RTL) $(GPC43_RTL) $(BTB2_RTL) $(MDUCFG_RTL) $(CHIPLINK_RTL); do \
	  if [ ! -f "$$dir/ysyx_26030103.sv" ]; then \
	    echo "缺少 RTL 快照 $$dir；先执行 ./elaborate-all.sh && ./elaborate-sram.sh" >&2; \
	    exit 1; \
	  fi; \
	done

# 运行全部审计探针；某个探针失败不会跳过其余探针。
test run: all
	@status=0; \
	for binary in $(BINS); do \
	  echo "---- $$binary"; \
	  "$$binary" || status=1; \
	done; \
	exit $$status

$(IDU_BIN): idu_perf_counter_test.cpp test_common.hpp $(BASE_RTL)/ysyx_26030103.sv rtl-check
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VFLAGS) \
	  --top-module ysyx_26030103_IDU \
	  --Mdir $(BUILD_DIR)/obj-idu-audit \
	  -I$(BASE_RTL) \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(BASE_RTL)/ysyx_26030103.sv idu_perf_counter_test.cpp

$(IRQ_BIN): interrupt_pulse_test.cpp test_common.hpp $(BASE_RTL)/ysyx_26030103.sv rtl-check
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VFLAGS) --assert \
	  --top-module ysyx_26030103_EXU \
	  --Mdir $(BUILD_DIR)/obj-irq-audit \
	  -I$(BASE_RTL) \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(BASE_RTL)/ysyx_26030103.sv interrupt_pulse_test.cpp

$(DCACHE_REG_BIN): dcache_response_slot_test.cpp test_common.hpp $(BASE_RTL)/ysyx_26030103.sv rtl-check
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VFLAGS) --assert \
	  --top-module ysyx_26030103_DCacheReg \
	  --Mdir $(BUILD_DIR)/obj-dcache-reg-audit \
	  -I$(BASE_RTL) \
	  -CFLAGS '$(CXXFLAGS) -DTEST_STORAGE_REG' \
	  -o $(abspath $@) \
	  $(BASE_RTL)/ysyx_26030103.sv dcache_response_slot_test.cpp

$(DCACHE_SRAM_BIN): dcache_response_slot_test.cpp test_common.hpp $(SRAM_RTL)/ysyx_26030103.sv rtl-check
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VFLAGS) --assert \
	  --top-module ysyx_26030103_DCacheSRAM \
	  --Mdir $(BUILD_DIR)/obj-dcache-sram-audit \
	  -I$(SRAM_RTL) \
	  -CFLAGS '$(CXXFLAGS) -DTEST_STORAGE_SRAM' \
	  -o $(abspath $@) \
	  $(SRAM_RTL)/ysyx_26030103.sv dcache_response_slot_test.cpp

$(DCACHE_NOBYPASS_BIN): dcache_response_slot_test.cpp test_common.hpp $(NOBYPASS_RTL)/ysyx_26030103.sv rtl-check
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VFLAGS) --assert \
	  --top-module ysyx_26030103_DCacheSRAM \
	  --Mdir $(BUILD_DIR)/obj-dcache-nobypass-audit \
	  -I$(NOBYPASS_RTL) \
	  -CFLAGS '$(CXXFLAGS) -DTEST_STORAGE_NOBYPASS' \
	  -o $(abspath $@) \
	  $(NOBYPASS_RTL)/ysyx_26030103.sv dcache_response_slot_test.cpp

$(ICACHE_BIN): icache_outstanding_test.cpp test_common.hpp $(BASE_RTL)/ysyx_26030103.sv rtl-check
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VFLAGS) \
	  --top-module ysyx_26030103_ICacheReg \
	  --Mdir $(BUILD_DIR)/obj-icache-audit \
	  -I$(BASE_RTL) \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(BASE_RTL)/ysyx_26030103.sv icache_outstanding_test.cpp

$(ICACHE_SMALL_BIN): icache_outstanding_test.cpp test_common.hpp $(SMALLLINE_RTL)/ysyx_26030103.sv rtl-check
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VFLAGS) \
	  --top-module ysyx_26030103_ICacheReg \
	  --Mdir $(BUILD_DIR)/obj-icache-smallline-audit \
	  -I$(SMALLLINE_RTL) \
	  -CFLAGS '$(CXXFLAGS) -DTEST_WORDS_PER_LINE=1' \
	  -o $(abspath $@) \
	  $(SMALLLINE_RTL)/ysyx_26030103.sv icache_outstanding_test.cpp

clean:
	rm -rf $(BUILD_DIR)/bin $(BUILD_DIR)/obj-*-audit

$(MUL_DEFAULT_BIN): mul_gpc_config_test.cpp test_common.hpp $(GPC32_RTL)/ysyx_26030103.sv rtl-check
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VFLAGS) \
	  --top-module ysyx_26030103_MULWallaceCore \
	  --Mdir $(BUILD_DIR)/obj-mul-gpc32-audit \
	  -I$(GPC32_RTL) \
	  -CFLAGS '$(CXXFLAGS) -DGPC_DEFAULT' \
	  -o $(abspath $@) \
	  $(GPC32_RTL)/ysyx_26030103.sv mul_gpc_config_test.cpp

$(MUL_ALT_BIN): mul_gpc_config_test.cpp test_common.hpp $(GPC43_RTL)/ysyx_26030103.sv rtl-check
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VFLAGS) \
	  --top-module ysyx_26030103_MULWallaceCore \
	  --Mdir $(BUILD_DIR)/obj-mul-gpc43-audit \
	  -I$(GPC43_RTL) \
	  -CFLAGS '$(CXXFLAGS) -DGPC_ALT' \
	  -o $(abspath $@) \
	  $(GPC43_RTL)/ysyx_26030103.sv mul_gpc_config_test.cpp

$(MCYCLE_BIN): mcycle_semantics_test.cpp test_common.hpp $(BASE_RTL)/ysyx_26030103.sv rtl-check
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VFLAGS) \
	  --top-module ysyx_26030103_mcycle \
	  --Mdir $(BUILD_DIR)/obj-mcycle-audit \
	  -I$(BASE_RTL) \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(BASE_RTL)/ysyx_26030103.sv mcycle_semantics_test.cpp

$(AXIRAM_BIN): axiram_window_test.cpp test_common.hpp $(BASE_RTL)/riscv32e_npc_AXIRAM.sv rtl-check
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VFLAGS) \
	  --top-module riscv32e_npc_AXIRAM \
	  --Mdir $(BUILD_DIR)/obj-axiram-audit \
	  -I$(BASE_RTL) \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(BASE_RTL)/riscv32e_npc_AXIRAM.sv axiram_window_test.cpp

$(BTB2_BIN): btb_twoway_test.cpp test_common.hpp $(BTB2_RTL)/ysyx_26030103.sv rtl-check
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VFLAGS) \
	  --top-module ysyx_26030103_BTB \
	  --Mdir $(BUILD_DIR)/obj-btb2-audit \
	  -I$(BTB2_RTL) \
	  -CFLAGS '$(CXXFLAGS) -DBTB_TWO_WAY' \
	  -o $(abspath $@) \
	  $(BTB2_RTL)/ysyx_26030103.sv btb_twoway_test.cpp

$(DCACHE_REFILL_REG_BIN): dcache_refill_interaction_test.cpp test_common.hpp $(BASE_RTL)/ysyx_26030103.sv rtl-check
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VFLAGS) --assert \
	  --top-module ysyx_26030103_DCacheReg \
	  --Mdir $(BUILD_DIR)/obj-dcache-refill-reg \
	  -I$(BASE_RTL) \
	  -CFLAGS '$(CXXFLAGS) -DTEST_STORAGE_REG' \
	  -o $(abspath $@) \
	  $(BASE_RTL)/ysyx_26030103.sv dcache_refill_interaction_test.cpp

$(DCACHE_REFILL_SRAM_BIN): dcache_refill_interaction_test.cpp test_common.hpp $(SRAM_RTL)/ysyx_26030103.sv rtl-check
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VFLAGS) --assert \
	  --top-module ysyx_26030103_DCacheSRAM \
	  --Mdir $(BUILD_DIR)/obj-dcache-refill-sram \
	  -I$(SRAM_RTL) \
	  -CFLAGS '$(CXXFLAGS) -DTEST_STORAGE_SRAM' \
	  -o $(abspath $@) \
	  $(SRAM_RTL)/ysyx_26030103.sv dcache_refill_interaction_test.cpp

$(DCACHE_REFILL_NOBYPASS_BIN): dcache_refill_interaction_test.cpp test_common.hpp $(NOBYPASS_RTL)/ysyx_26030103.sv rtl-check
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VFLAGS) --assert \
	  --top-module ysyx_26030103_DCacheSRAM \
	  --Mdir $(BUILD_DIR)/obj-dcache-refill-nobypass \
	  -I$(NOBYPASS_RTL) \
	  -CFLAGS '$(CXXFLAGS) -DTEST_STORAGE_NOBYPASS' \
	  -o $(abspath $@) \
	  $(NOBYPASS_RTL)/ysyx_26030103.sv dcache_refill_interaction_test.cpp

$(MULCFG_BIN): mdu_config_core_test.cpp test_common.hpp $(MDUCFG_RTL)/ysyx_26030103.sv rtl-check
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VFLAGS) \
	  --top-module ysyx_26030103_MULShiftAddCore \
	  --Mdir $(BUILD_DIR)/obj-mulcfg-audit \
	  -I$(MDUCFG_RTL) \
	  -CFLAGS '$(CXXFLAGS) -DTEST_MUL_CORE' \
	  -o $(abspath $@) \
	  $(MDUCFG_RTL)/ysyx_26030103.sv mdu_config_core_test.cpp

$(DIVCFG_BIN): mdu_config_core_test.cpp test_common.hpp $(MDUCFG_RTL)/ysyx_26030103.sv rtl-check
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VFLAGS) \
	  --top-module ysyx_26030103_DIVNonRestoringCore \
	  --Mdir $(BUILD_DIR)/obj-divcfg-audit \
	  -I$(MDUCFG_RTL) \
	  -CFLAGS '$(CXXFLAGS) -DTEST_DIV_CORE' \
	  -o $(abspath $@) \
	  $(MDUCFG_RTL)/ysyx_26030103.sv mdu_config_core_test.cpp

$(MDU_BLOCK_BIN): mdu_pipeline_block_test.cpp test_common.hpp $(BASE_RTL)/ysyx_26030103.sv rtl-check
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VFLAGS) --assert \
	  --top-module ysyx_26030103_EXU \
	  --Mdir $(BUILD_DIR)/obj-mdu-block-audit \
	  -I$(BASE_RTL) \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(BASE_RTL)/ysyx_26030103.sv mdu_pipeline_block_test.cpp

# 全系统测量：用生产 wrapper（仓库内 rtl/riscv32e_npc_SimTop.sv）+ 当前 RTL 快照
$(FULLCORE_BIN): fullcore_stall_test.cpp test_common.hpp \
                 $(NPC_DIR)/rtl/riscv32e_npc_SimTop.sv \
                 $(BASE_RTL)/ysyx_26030103_npc.sv $(BASE_RTL)/riscv32e_npc_AXIRAM.sv rtl-check
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VFLAGS) \
	  --top-module riscv32e_npc_SimTop \
	  --Mdir $(BUILD_DIR)/obj-fullcore-audit \
	  -I$(BASE_RTL) -I$(NPC_DIR)/rtl \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(NPC_DIR)/rtl/riscv32e_npc_SimTop.sv \
	  $(BASE_RTL)/ysyx_26030103_npc.sv $(BASE_RTL)/riscv32e_npc_AXIRAM.sv \
	  fullcore_stall_test.cpp

# perf 口径回归：同样使用生产 wrapper + NPC 快照
$(PERFSEM_BIN): perf_counter_semantics_test.cpp test_common.hpp \
                $(NPC_DIR)/rtl/riscv32e_npc_SimTop.sv \
                $(BASE_RTL)/ysyx_26030103_npc.sv $(BASE_RTL)/riscv32e_npc_AXIRAM.sv rtl-check
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VFLAGS) \
	  --top-module riscv32e_npc_SimTop \
	  --Mdir $(BUILD_DIR)/obj-perfsem-audit \
	  -I$(BASE_RTL) -I$(NPC_DIR)/rtl \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(NPC_DIR)/rtl/riscv32e_npc_SimTop.sv \
	  $(BASE_RTL)/ysyx_26030103_npc.sv $(BASE_RTL)/riscv32e_npc_AXIRAM.sv \
	  perf_counter_semantics_test.cpp

$(XBAR_HOL_BIN): xbar_r_hol_test.cpp test_common.hpp $(BASE_RTL)/ysyx_26030103.sv rtl-check
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VFLAGS) --assert \
	  --top-module ysyx_26030103_AXI5Xbar \
	  --Mdir $(BUILD_DIR)/obj-xbar-hol-audit \
	  -I$(BASE_RTL) \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(BASE_RTL)/ysyx_26030103.sv xbar_r_hol_test.cpp

# CHIPLINK=y 的 SoC 顶层（DMA 代理启用），用于 DMA/缓存一致性窗口测试
$(CHIPLINK_RTL)/ysyx_26030103.sv:
	@mkdir -p $(CHIPLINK_RTL)
	cd $(NPC_DIR) && env \
	  CACHE_BLOCK_SIZE_LOG2=2 CACHE_INDEX_BITS=5 \
	  ICACHE=n DCACHE=y WBUF_DEPTH=4 CACHE_PADDING=0 CHIPLINK=y \
	  BTB_BITS=4 BTB_WAYS=1 JAL_BTB_BITS=4 JAL_BTB_WAYS=1 RAS_BITS=4 \
	  RV32_M=y RV32_A=n RV32_C=n SDB=y DIFFTEST=y PERF_STATS=y \
	  mill --no-server runMain ysyx_26030103_Elaborate --target-dir $(CHIPLINK_RTL)
	ln -sf layers-ysyx_26030103-Verification.sv $(CHIPLINK_RTL)/layers-riscv32e_npc_AXIRAM-Verification.sv

DMA_WINDOW_DIAG_BIN := $(BIN_DIR)/dma_coherence_diag_test
DCACHE_SMALL_BIN := $(BIN_DIR)/dcache_smallline_test

$(DMA_WINDOW_BIN): dma_coherence_window_test.cpp test_common.hpp \
                   $(CHIPLINK_RTL)/ysyx_26030103.sv rtl-check
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VFLAGS) --assert \
	  --top-module ysyx_26030103 \
	  --Mdir $(BUILD_DIR)/obj-dma-window-audit \
	  -I$(CHIPLINK_RTL) \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(CHIPLINK_RTL)/ysyx_26030103.sv dma_coherence_window_test.cpp

$(DMA_WINDOW_DIAG_BIN): dma_coherence_window_test.cpp test_common.hpp \
                        $(CHIPLINK_RTL)/ysyx_26030103.sv rtl-check
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VFLAGS) --assert \
	  --top-module ysyx_26030103 \
	  --Mdir $(BUILD_DIR)/obj-dma-diag-audit \
	  -I$(CHIPLINK_RTL) \
	  -CFLAGS '$(CXXFLAGS) -DDIAG_DMA_AFTER_ALL' \
	  -o $(abspath $@) \
	  $(CHIPLINK_RTL)/ysyx_26030103.sv dma_coherence_window_test.cpp

$(DCACHE_SMALL_BIN): dcache_smallline_test.cpp test_common.hpp $(SMALLLINE_RTL)/ysyx_26030103.sv rtl-check
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VFLAGS) --assert \
	  --top-module ysyx_26030103_DCacheReg \
	  --Mdir $(BUILD_DIR)/obj-dcache-small-audit \
	  -I$(SMALLLINE_RTL) \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(SMALLLINE_RTL)/ysyx_26030103.sv dcache_smallline_test.cpp


DMA_WINDOW_NOLOAD_BIN := $(BIN_DIR)/dma_coherence_noload_diag
$(DMA_WINDOW_NOLOAD_BIN): dma_coherence_window_test.cpp test_common.hpp \
                          $(CHIPLINK_RTL)/ysyx_26030103.sv rtl-check
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VFLAGS) --assert \
	  --top-module ysyx_26030103 \
	  --Mdir $(BUILD_DIR)/obj-dma-noload-audit \
	  -I$(CHIPLINK_RTL) \
	  -CFLAGS '$(CXXFLAGS) -DDIAG_NO_LOADS -DDIAG_DMA_AFTER_ALL' \
	  -o $(abspath $@) \
	  $(CHIPLINK_RTL)/ysyx_26030103.sv dma_coherence_window_test.cpp

DMA_WINDOW_TRACE_BIN := $(BIN_DIR)/dma_coherence_trace_test
$(DMA_WINDOW_TRACE_BIN): dma_coherence_window_test.cpp test_common.hpp \
                         $(CHIPLINK_RTL)/ysyx_26030103.sv rtl-check
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VFLAGS) --assert --trace \
	  --top-module ysyx_26030103 \
	  --Mdir $(BUILD_DIR)/obj-dma-trace-audit \
	  -I$(CHIPLINK_RTL) \
	  -CFLAGS '$(CXXFLAGS) -DDIAG_TRACE -DDIAG_DMA_AFTER_ALL' \
	  -o $(abspath $@) \
	  $(CHIPLINK_RTL)/ysyx_26030103.sv dma_coherence_window_test.cpp

# 生产配置（sram+sram, 32B/64sets）下的 ICacheSRAM 覆盖：先跑 ./elaborate-prod.sh
$(ICACHE_SRAM_BIN): icache_sram_test.cpp test_common.hpp $(PROD_RTL)/ysyx_26030103.sv
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VFLAGS) --assert \
	  --top-module ysyx_26030103_ICacheSRAM \
	  --Mdir $(BUILD_DIR)/obj-icache-sram-audit \
	  -I$(PROD_RTL) \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(PROD_RTL)/ysyx_26030103.sv icache_sram_test.cpp

# 同一 EXU 叶子在"快 MDU 配置"（booth/iter=4/split=2/early-out）下的阻塞量化
$(MDU_BLOCK_FAST_BIN): mdu_pipeline_block_test.cpp test_common.hpp $(MDUCFG_RTL)/ysyx_26030103.sv rtl-check
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VFLAGS) --assert \
	  --top-module ysyx_26030103_EXU \
	  --Mdir $(BUILD_DIR)/obj-mdu-block-fast-audit \
	  -I$(MDUCFG_RTL) \
	  -CFLAGS '$(CXXFLAGS) -DTEST_FAST_MDU' \
	  -o $(abspath $@) \
	  $(MDUCFG_RTL)/ysyx_26030103.sv mdu_pipeline_block_test.cpp

# 混合几何/混合存储配置（ICache 16B/32sets/sram + DCache 32B/64sets/reg）
# 的端到端烟测：先跑 ./elaborate-mixed.sh
$(PERFSEM_MIXED_BIN): perf_counter_semantics_test.cpp test_common.hpp \
                      $(NPC_DIR)/rtl/riscv32e_npc_SimTop.sv \
                      $(MIXED_RTL)/ysyx_26030103_npc.sv $(MIXED_RTL)/riscv32e_npc_AXIRAM.sv
	@mkdir -p $(BIN_DIR)
	$(VERILATOR) $(VFLAGS) \
	  --top-module riscv32e_npc_SimTop \
	  --Mdir $(BUILD_DIR)/obj-perfsem-mixed-audit \
	  -I$(MIXED_RTL) -I$(NPC_DIR)/rtl \
	  -CFLAGS '$(CXXFLAGS)' \
	  -o $(abspath $@) \
	  $(NPC_DIR)/rtl/riscv32e_npc_SimTop.sv \
	  $(MIXED_RTL)/ysyx_26030103_npc.sv $(MIXED_RTL)/riscv32e_npc_AXIRAM.sv \
	  perf_counter_semantics_test.cpp
