SHELL := /bin/bash

NPC_DIR := $(abspath ../../..)
VGA_GUEST_DIR := $(abspath ../vga-test)
VGA_GUEST := $(VGA_GUEST_DIR)/build/vga-test-riscv32i-ysyxsoc.bin
BUILD_DIR := $(CURDIR)/build
NPC_BUILD_DIR := $(BUILD_DIR)/npc-build
NPC_BIN := $(NPC_BUILD_DIR)/ysyx_26030103
RUN_DIR := $(BUILD_DIR)/run

# Y=36/X=146 in the first complete raster frame.  At this visible sample the
# test-only build inverts both HSYNC and VALID, producing a one-cycle sync
# glitch and one genuinely missing visible coordinate.
FAULT_CYCLE := 28145
TEST_CXXFLAGS := -std=c++26 -fmodules \
	-DNPC_VGA_CHECK_TEST_INJECT_CYCLE=$(FAULT_CYCLE)

.PHONY: all test clean guest simulator

all: test

guest:
	$(MAKE) -C $(VGA_GUEST_DIR) ARCH=riscv32i-ysyxsoc

simulator:
	$(MAKE) -C $(NPC_DIR) git_commit= SIM_TOP=soc \
	  CONFIG_SDB=n CONFIG_DIFFTEST=n CONFIG_PERF_STATS=n CONFIG_PERF_SAVE=n \
	  CONFIG_LOG_LEVEL=0 CONFIG_LOG_TO_FILE=n \
	  USER_CXXFLAGS='$(TEST_CXXFLAGS)' \
	  BUILD_DIR=$(abspath $(NPC_BUILD_DIR)) $(abspath $(NPC_BIN))

test: guest simulator
	rm -rf $(RUN_DIR)
	mkdir -p $(RUN_DIR)
	@cd $(RUN_DIR); set +e; \
	  $(abspath $(NPC_BIN)) $(VGA_GUEST) --vga-check > output.log 2>&1; \
	  status=$$?; set -e; test $$status -eq 1
	grep -Eq 'HSYNC脉宽异常.*: [1-9][0-9]*' $(RUN_DIR)/output.log
	grep -Eq 'HSYNC光栅电平错误拍数: [1-9][0-9]*' $(RUN_DIR)/output.log
	grep -Eq '有效区/porch时序错误拍数: [1-9][0-9]*' $(RUN_DIR)/output.log
	grep -Eq '累计缺失/重复像素: [1-9][0-9]*/[0-9]+' $(RUN_DIR)/output.log
	grep -Fq 'VGA时序检查: FAIL' $(RUN_DIR)/output.log
	grep -Fq 'HIT BAD TRAP' $(RUN_DIR)/output.log
	@echo '[PASS] VGA checker rejects injected sync and pixel-coverage faults'

clean:
	rm -rf $(BUILD_DIR)
