# NEMU DiffTest tval ABI 直连回归。直接 dlopen REF，不依赖 NPC RTL。
#   make REF_SO=/path/to/riscv64-nemu-interpreter-so run
SHELL := /bin/bash
TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
REF_SO ?= $(TEST_DIR)/../../../../../nemu/build/riscv64-nemu-interpreter-so
REF_SO_RV32 ?= $(BUILD_DIR)/riscv32-nemu-interpreter-so
HOST_CXX ?= g++
BUILD_DIR := $(TEST_DIR)/build
TEST_BIN := $(BUILD_DIR)/difftest-tval-abi-test
ROOT_DIR := $(abspath $(TEST_DIR)/../../../../..)
NEMU_DIR := $(ROOT_DIR)/nemu
REF_OBJ_DIR := $(BUILD_DIR)/nemu-ref-obj
REF_OBJ_RV32_DIR := $(BUILD_DIR)/nemu-ref-rv32-obj

include $(TEST_DIR)/../common/nemu_ref.mk

.PHONY: all run run-rv32 clean

all: $(TEST_BIN) $(REF_SO)

$(eval $(call nemu_ref_rule,$(REF_SO),riscv64,$(REF_OBJ_DIR),share))
$(eval $(call nemu_ref_rule,$(REF_SO_RV32),riscv32,$(REF_OBJ_RV32_DIR),share))

$(TEST_BIN): main.cpp | $(BUILD_DIR)
	$(HOST_CXX) -std=c++20 -O2 -Wall -Wextra -Werror -o $@ $< -ldl

$(BUILD_DIR):
	mkdir -p $@

run: all
	@test -f "$(REF_SO)" || { echo "REF 不存在: $(REF_SO)"; exit 1; }
	$(TEST_BIN) "$(REF_SO)"

run-rv32: $(TEST_BIN) $(REF_SO_RV32)
	@test -f "$(REF_SO_RV32)" || { echo "REF 不存在: $(REF_SO_RV32)"; exit 1; }
	$(TEST_BIN) "$(REF_SO_RV32)"

clean:
	rm -rf $(BUILD_DIR)
