SHELL := /bin/bash

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
ROOT_DIR := $(abspath $(TEST_DIR)/../../../..)
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
NEMU_SRCS := $(shell find $(NEMU_DIR)/src $(NEMU_DIR)/include -type f \( -name '*.c' -o -name '*.h' \) -print)

NAME = nemu-difftest-isolation-test
SRCS = main.cpp

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

$(REF_SO): $(NEMU_SRCS) $(TEST_DIR)/Makefile
	$(MAKE) -C $(NEMU_DIR) GUEST_ISA=riscv32 ENGINE=interpreter \
		CONFIG_TARGET_SHARE=y CONFIG_TARGET_NATIVE_ELF= \
		BINARY=$(REF_SO) OBJ_DIR=$(REF_OBJ_DIR)

$(NEMU_RVE_BIN): $(NEMU_SRCS) $(TEST_DIR)/Makefile
	$(MAKE) -C $(NEMU_DIR) GUEST_ISA=riscv32 ENGINE=interpreter \
		CONFIG_TARGET_SHARE= CONFIG_TARGET_NATIVE_ELF=y CONFIG_DIFFTEST=y CONFIG_RVE=y \
		CFLAGS_BUILD='-DCONFIG_DIFFTEST -DCONFIG_RVE' \
		BINARY=$(NEMU_RVE_BIN) OBJ_DIR=$(NEMU_RVE_OBJ_DIR)

$(NEMU_SOC_BIN): $(NEMU_SRCS) $(TEST_DIR)/Makefile
	$(MAKE) -C $(NEMU_DIR) GUEST_ISA=riscv32 ENGINE=interpreter \
		CONFIG_TARGET_SHARE= CONFIG_TARGET_NATIVE_ELF=y CONFIG_DIFFTEST=y CONFIG_RVE= \
		CFLAGS_BUILD=-DCONFIG_DIFFTEST \
		BINARY=$(NEMU_SOC_BIN) OBJ_DIR=$(NEMU_SOC_OBJ_DIR)
