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

include $(TEST_DIR)/../common/nemu_ref.mk

CXX ?= g++

.PHONY: all run clean

all: $(BIN) $(REF_SO) $(REF_RVE_SO)

run: all
	$(BIN) $(REF_SO)
	$(BIN) $(REF_RVE_SO) --rve

$(eval $(call nemu_ref_rule,$(REF_SO),riscv32,$(REF_OBJ_DIR),share))
$(eval $(call nemu_ref_rule,$(REF_RVE_SO),riscv32e,$(REF_RVE_OBJ_DIR),share,0,1))

$(BIN): main.cpp
	mkdir -p $(BUILD_DIR)
	$(CXX) -std=c++20 -O2 -Wall -Wextra -Werror -o $@ $< -ldl

clean:
	rm -rf $(BUILD_DIR)
