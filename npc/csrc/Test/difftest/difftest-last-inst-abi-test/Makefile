# NEMU REF 可选诊断 ABI 回归（last-instruction + CSR getter）。
# 需要分别提供 RV32/RV64 REF .so：
#   make run REF32=/path/riscv32-nemu-interpreter-so \
#            REF64=/path/riscv64-nemu-interpreter-so
SHELL := /bin/bash
TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
BUILD_DIR := $(TEST_DIR)/build
TEST_BIN := $(BUILD_DIR)/difftest-last-inst-abi-test
REF32 ?= $(TEST_DIR)/../../../../../nemu/build/riscv32-nemu-interpreter-so
REF64 ?= $(TEST_DIR)/../../../../../nemu/build/riscv64-nemu-interpreter-so
HOST_CXX ?= g++
ROOT_DIR := $(abspath $(TEST_DIR)/../../../../..)
NEMU_DIR := $(ROOT_DIR)/nemu
NEMU_SRCS := $(shell find $(NEMU_DIR)/src $(NEMU_DIR)/include -type f \( -name '*.c' -o -name '*.h' \) -print)
REF_BUILDER := $(TEST_DIR)/../common/build_nemu_ref.sh
REF32_OBJ_DIR := $(BUILD_DIR)/nemu-ref32-obj
REF64_OBJ_DIR := $(BUILD_DIR)/nemu-ref64-obj

.PHONY: all run clean

all: $(TEST_BIN) $(REF32) $(REF64)

$(REF32): $(NEMU_SRCS) $(TEST_DIR)/Makefile $(REF_BUILDER)
	$(REF_BUILDER) riscv32 $@ $(REF32_OBJ_DIR) share

$(REF64): $(NEMU_SRCS) $(TEST_DIR)/Makefile $(REF_BUILDER)
	$(REF_BUILDER) riscv64 $@ $(REF64_OBJ_DIR) share

$(TEST_BIN): main.cpp | $(BUILD_DIR)
	$(HOST_CXX) -std=c++20 -O2 -Wall -Wextra -Werror -o $@ $< -ldl

$(BUILD_DIR):
	mkdir -p $@

run: all
	@test -f "$(REF32)" || { echo "RV32 REF 不存在: $(REF32)"; exit 1; }
	@test -f "$(REF64)" || { echo "RV64 REF 不存在: $(REF64)"; exit 1; }
	$(TEST_BIN) "$(REF32)"
	$(TEST_BIN) "$(REF64)"

clean:
	rm -rf $(BUILD_DIR)
