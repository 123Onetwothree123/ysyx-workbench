# NEMU DiffTest tval ABI 直连回归。直接 dlopen REF，不依赖 NPC RTL。
#   make REF_SO=/path/to/riscv64-nemu-interpreter-so run
SHELL := /bin/bash
TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
REF_SO ?= $(TEST_DIR)/../../../../nemu/build/riscv64-nemu-interpreter-so
HOST_CXX ?= g++
BUILD_DIR := $(TEST_DIR)/build
TEST_BIN := $(BUILD_DIR)/difftest-tval-abi-test

.PHONY: all run clean

all: $(TEST_BIN)

$(TEST_BIN): main.cpp | $(BUILD_DIR)
	$(HOST_CXX) -std=c++20 -O2 -Wall -Wextra -Werror -o $@ $< -ldl

$(BUILD_DIR):
	mkdir -p $@

run: $(TEST_BIN)
	@test -f "$(REF_SO)" || { echo "REF 不存在: $(REF_SO)"; exit 1; }
	$(TEST_BIN) "$(REF_SO)"

clean:
	rm -rf $(BUILD_DIR)
