SHELL := /bin/bash

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
ROOT_DIR := $(abspath $(TEST_DIR)/../../../..)
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

$(REF_SO): $(NEMU_SRCS) $(TEST_DIR)/Makefile
	$(MAKE) -C $(NEMU_DIR) GUEST_ISA=riscv32 ENGINE=interpreter \
		CONFIG_TARGET_SHARE=y CONFIG_TARGET_NATIVE_ELF= \
		BINARY=$(REF_SO) OBJ_DIR=$(REF_OBJ_DIR)

clean:
	rm -rf $(BUILD_DIR)
