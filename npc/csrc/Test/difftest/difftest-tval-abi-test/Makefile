# NEMU DiffTest tval ABI 直连回归。直接 dlopen REF，不依赖 NPC RTL。
#   make REF_SO=/path/to/riscv64-nemu-interpreter-so run
SHELL := /bin/bash
TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
REF_SO ?= $(TEST_DIR)/../../../../../nemu/build/riscv64-nemu-interpreter-so
HOST_CXX ?= g++
BUILD_DIR := $(TEST_DIR)/build
TEST_BIN := $(BUILD_DIR)/difftest-tval-abi-test
ROOT_DIR := $(abspath $(TEST_DIR)/../../../../..)
NEMU_DIR := $(ROOT_DIR)/nemu
NEMU_SRCS := $(shell find $(NEMU_DIR)/src $(NEMU_DIR)/include -type f \( -name '*.c' -o -name '*.h' \) -print)
REF_BUILDER := $(TEST_DIR)/../common/build_nemu_ref.sh
REF_OBJ_DIR := $(BUILD_DIR)/nemu-ref-obj

.PHONY: all run clean

all: $(TEST_BIN) $(REF_SO)

$(REF_SO): $(NEMU_SRCS) $(TEST_DIR)/Makefile $(REF_BUILDER)
	$(REF_BUILDER) riscv64 $@ $(REF_OBJ_DIR) share

$(TEST_BIN): main.cpp | $(BUILD_DIR)
	$(HOST_CXX) -std=c++20 -O2 -Wall -Wextra -Werror -o $@ $< -ldl

$(BUILD_DIR):
	mkdir -p $@

run: all
	@test -f "$(REF_SO)" || { echo "REF 不存在: $(REF_SO)"; exit 1; }
	$(TEST_BIN) "$(REF_SO)"

clean:
	rm -rf $(BUILD_DIR)
