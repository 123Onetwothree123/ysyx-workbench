SHELL := /bin/bash

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
NPC_DIR := $(abspath $(TEST_DIR)/../../../..)
ROOT_DIR := $(abspath $(NPC_DIR)/..)
NEMU_DIR := $(ROOT_DIR)/nemu
BUILD_DIR := $(TEST_DIR)/build
BIN := $(BUILD_DIR)/difftest_abi_test
REF_SO := $(BUILD_DIR)/riscv32-nemu-interpreter-so
REF_OBJ_DIR := $(BUILD_DIR)/nemu-obj
REF_RVE_SO := $(BUILD_DIR)/riscv32e-nemu-interpreter-so
REF_RVE_OBJ_DIR := $(BUILD_DIR)/nemu-rve-fixed-obj
NEMU_SRCS := $(shell find $(NEMU_DIR)/src $(NEMU_DIR)/include -type f \( -name '*.c' -o -name '*.h' \) -print)

CXX ?= g++

.PHONY: all run clean

all: $(BIN) $(REF_SO) $(REF_RVE_SO)

run: all
	$(BIN) $(REF_SO)
	$(BIN) $(REF_RVE_SO) --rve

REF_BUILDER := $(TEST_DIR)/../common/build_nemu_ref.sh

$(REF_SO): $(NEMU_SRCS) $(TEST_DIR)/Makefile $(REF_BUILDER)
	$(REF_BUILDER) riscv32 $@ $(REF_OBJ_DIR) share

$(REF_RVE_SO): $(NEMU_SRCS) $(TEST_DIR)/Makefile $(REF_BUILDER)
	$(REF_BUILDER) riscv32e $@ $(REF_RVE_OBJ_DIR) share 0 1

$(BIN): main.cpp
	mkdir -p $(BUILD_DIR)
	$(CXX) -std=c++20 -O2 -Wall -Wextra -Werror -o $@ $< -ldl

clean:
	rm -rf $(BUILD_DIR)
