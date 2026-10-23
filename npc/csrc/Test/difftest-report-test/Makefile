# DiffTest 失败报告格式化单测：不依赖 RTL/Verilator。
#   make run
SHELL := /bin/bash
TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
BUILD_DIR := $(TEST_DIR)/build
TEST_BIN := $(BUILD_DIR)/difftest-report-test
HEADER := $(TEST_DIR)/../../difftest/DifftestReport.h
HOST_CXX ?= g++

.PHONY: all run clean

all: $(TEST_BIN)

$(TEST_BIN): main.cpp $(HEADER) | $(BUILD_DIR)
	$(HOST_CXX) -std=c++26 -O2 -Wall -Wextra -Werror \
		-D_GLIBCXX_ASSERTIONS -o $@ main.cpp

$(BUILD_DIR):
	mkdir -p $@

run: $(TEST_BIN)
	$(TEST_BIN)

clean:
	rm -rf $(BUILD_DIR)
