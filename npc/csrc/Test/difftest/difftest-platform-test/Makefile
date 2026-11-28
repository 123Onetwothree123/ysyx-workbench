SHELL := /bin/bash

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
ROOT_DIR := $(abspath $(TEST_DIR)/../../../../..)
NEMU_DIR := $(ROOT_DIR)/nemu
BUILD_DIR := $(TEST_DIR)/build
BIN := $(BUILD_DIR)/difftest_platform_test
REF_SO := $(BUILD_DIR)/riscv32-nemu-interpreter-so
REF_OBJ_DIR := $(BUILD_DIR)/nemu-obj

include $(TEST_DIR)/../common/nemu_ref.mk

CXX ?= g++

.PHONY: all run clean

all: $(BIN) $(REF_SO)

run: all
	$(BIN) $(REF_SO)

$(BIN): main.cpp
	mkdir -p $(@D)
	$(CXX) -std=c++20 -O2 -Wall -Wextra -Werror -D_GLIBCXX_ASSERTIONS -o $@ $< -ldl

$(eval $(call nemu_ref_rule,$(REF_SO),riscv32,$(REF_OBJ_DIR),share))

clean:
	rm -rf $(BUILD_DIR)
