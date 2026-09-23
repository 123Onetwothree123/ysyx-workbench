SHELL := /bin/bash

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
NPC_DIR := $(abspath $(TEST_DIR)/../../..)
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

$(REF_SO): $(NEMU_SRCS) $(TEST_DIR)/Makefile
	$(MAKE) -C $(NEMU_DIR) GUEST_ISA=riscv32 ENGINE=interpreter \
		CONFIG_TARGET_SHARE=y CONFIG_TARGET_NATIVE_ELF= \
		BINARY=$(REF_SO) OBJ_DIR=$(REF_OBJ_DIR)

$(REF_RVE_SO): $(NEMU_SRCS) $(TEST_DIR)/Makefile
	$(MAKE) -C $(NEMU_DIR) GUEST_ISA=riscv32 ENGINE=interpreter \
		CONFIG_TARGET_SHARE=y CONFIG_TARGET_NATIVE_ELF= CONFIG_RVE=y \
		CFLAGS_BUILD=-DCONFIG_RVE \
		BINARY=$(REF_RVE_SO) OBJ_DIR=$(REF_RVE_OBJ_DIR)

$(BIN): main.cpp
	mkdir -p $(BUILD_DIR)
	$(CXX) -std=c++20 -O2 -Wall -Wextra -Werror -o $@ $< -ldl

clean:
	rm -rf $(BUILD_DIR)
