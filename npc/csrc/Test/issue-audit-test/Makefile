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
BINS := $(IDU_BIN) $(IRQ_BIN) $(DCACHE_REG_BIN) $(DCACHE_SRAM_BIN) \
        $(DCACHE_NOBYPASS_BIN) $(ICACHE_BIN) $(ICACHE_SMALL_BIN) \
        $(MUL_DEFAULT_BIN) $(MUL_ALT_BIN) $(MCYCLE_BIN)

.PHONY: all test run rtl-check clean

all: $(BINS)

# 先运行 ./elaborate-all.sh 与 ./elaborate-sram.sh 生成 RTL 快照。
rtl-check:
	@for dir in $(BASE_RTL) $(SRAM_RTL) $(NOBYPASS_RTL) $(SMALLLINE_RTL) \
	            $(GPC32_RTL) $(GPC43_RTL); do \
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
