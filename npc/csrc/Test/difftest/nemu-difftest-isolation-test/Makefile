SHELL := /bin/bash

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
ROOT_DIR := $(abspath $(TEST_DIR)/../../../../..)
NEMU_DIR := $(ROOT_DIR)/nemu
BUILD_DIR := $(TEST_DIR)/build
REF_SO := $(BUILD_DIR)/riscv32-nemu-interpreter-so
REF_OBJ_DIR := $(BUILD_DIR)/ref-obj
NEMU_RVE_BIN := $(BUILD_DIR)/riscv32e-nemu-interpreter
NEMU_RVE_OBJ_DIR := $(BUILD_DIR)/rve-dut-obj
NEMU_SOC_BIN := $(BUILD_DIR)/riscv32-nemu-ysyxsoc-interpreter
NEMU_SOC_OBJ_DIR := $(BUILD_DIR)/soc-dut-obj
RVE_LOG := $(BUILD_DIR)/rve-difftest.log
SOC_LOG := $(BUILD_DIR)/soc-difftest.log

include $(TEST_DIR)/../common/nemu_ref.mk

NAME = nemu-difftest-isolation-test
SRCS = main.cpp

# 本测试固定构建 RV32 NEMU REF；RV64 DiffTest 请用 xlen-dual-test。
include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../../xlen.mk)
ifeq ($(XLEN_IS_64),64)
$(error nemu-difftest-isolation-test 固定 RV32 REF；RV64 DiffTest 请用 xlen-dual-test)
endif

ARCH ?= riscv32e-npc
export ARCH

include $(AM_HOME)/Makefile

override CXXFLAGS += -std=c++17 -D_GLIBCXX_ASSERTIONS

.PHONY: check rve-check soc-check soc-check-inner

check: rve-check soc-check

rve-check: insert-arg $(REF_SO) $(NEMU_RVE_BIN)
	@set -o pipefail; cd $(NEMU_DIR); \
	  $(NEMU_RVE_BIN) -b --diff=$(REF_SO) $(IMAGE).bin 2>&1 | tee $(RVE_LOG)
	@grep -Fq 'Differential testing:' $(RVE_LOG)
	@grep -Fq 'HIT GOOD TRAP' $(RVE_LOG)

soc-check:
	$(MAKE) ARCH=riscv32i-ysyxsoc soc-check-inner

soc-check-inner: insert-arg $(REF_SO) $(NEMU_SOC_BIN)
	@set -o pipefail; cd $(NEMU_DIR); \
	  $(NEMU_SOC_BIN) --ysyxsoc -b --diff=$(REF_SO) $(IMAGE).bin 2>&1 | tee $(SOC_LOG)
	@grep -Fq 'Differential testing:' $(SOC_LOG)
	@grep -Fq 'HIT GOOD TRAP' $(SOC_LOG)

$(eval $(call nemu_ref_rule,$(REF_SO),riscv32,$(REF_OBJ_DIR),share))
$(eval $(call nemu_ref_rule,$(NEMU_RVE_BIN),riscv32e,$(NEMU_RVE_OBJ_DIR),native,1,1))
$(eval $(call nemu_ref_rule,$(NEMU_SOC_BIN),riscv32,$(NEMU_SOC_OBJ_DIR),native,1,0))
