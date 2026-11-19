SHELL := /bin/bash

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
ROOT_DIR := $(abspath $(TEST_DIR)/../../../../..)
NEMU_DIR := $(ROOT_DIR)/nemu
BUILD_DIR := $(TEST_DIR)/build
BIN := $(BUILD_DIR)/difftest_platform_test
REF_SO := $(BUILD_DIR)/riscv32-nemu-interpreter-so
REF_OBJ_DIR := $(BUILD_DIR)/nemu-obj
NEMU_SRCS := $(shell find $(NEMU_DIR)/src $(NEMU_DIR)/include -type f \( -name '*.c' -o -name '*.h' \) -print)
CXX ?= g++

.PHONY: all run clean

all: $(BIN) $(REF_SO)

run: all
	$(BIN) $(REF_SO)

$(BIN): main.cpp
	mkdir -p $(@D)
	$(CXX) -std=c++20 -O2 -Wall -Wextra -Werror -D_GLIBCXX_ASSERTIONS -o $@ $< -ldl

REF_BUILDER := $(TEST_DIR)/../common/build_nemu_ref.sh

$(REF_SO): $(NEMU_SRCS) $(TEST_DIR)/Makefile $(REF_BUILDER)
	$(REF_BUILDER) riscv32 $@ $(REF_OBJ_DIR) share

clean:
	rm -rf $(BUILD_DIR)
