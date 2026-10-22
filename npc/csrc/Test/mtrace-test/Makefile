# mtrace 64 位地址/数据格式回归（host 侧小测试，不构建 NPC）。
SHELL := /bin/bash

CXX ?= g++
CXXFLAGS ?= -std=c++26 -fmodules -O2 -Wall -Wextra -Werror

NPC_DIR := $(abspath ../../..)
BUILD_DIR := $(CURDIR)/build
BIN := $(BUILD_DIR)/mtrace-test
OUT := $(BUILD_DIR)/mtrace.log
STD_MODULE_SRC := $(lastword $(wildcard /usr/include/c++/*/bits/std.cc))

include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../xlen.mk)
CXXFLAGS += $(XLEN_CFLAGS)

.PHONY: all test clean

all: $(BIN)

test: $(BIN)
	$(BIN) >$(OUT) 2>&1
	grep -Fq 'Memory Write: PC=0x100000000, Addr=0x100002000, Data=0x8877665544332211, Width=8B' $(OUT)
	grep -Fq 'Memory Read:  PC=0x100000004, Addr=0x1ffffffff, Data=0xffffffffffffffff, Width=8B' $(OUT)
	@echo '[PASS] mtrace keeps 64-bit high PC/Addr/Data'

$(BUILD_DIR):
	mkdir -p $@

$(BUILD_DIR)/std.o: $(STD_MODULE_SRC) | $(BUILD_DIR)
	cd $(BUILD_DIR) && $(CXX) $(CXXFLAGS) -x c++ -c $(abspath $<) -o std.o

$(BUILD_DIR)/mtrace.ixx.o: $(NPC_DIR)/csrc/trace/mtrace.ixx $(BUILD_DIR)/std.o
	cd $(BUILD_DIR) && $(CXX) $(CXXFLAGS) -x c++ -c $< -o mtrace.ixx.o

$(BUILD_DIR)/mtrace.cpp.o: $(NPC_DIR)/csrc/trace/mtrace.cpp $(BUILD_DIR)/mtrace.ixx.o
	cd $(BUILD_DIR) && $(CXX) $(CXXFLAGS) -c $< -o mtrace.cpp.o

$(BUILD_DIR)/main.o: main.cpp $(BUILD_DIR)/mtrace.ixx.o
	cd $(BUILD_DIR) && $(CXX) $(CXXFLAGS) -c $(abspath $<) -o main.o

$(BIN): $(BUILD_DIR)/std.o $(BUILD_DIR)/mtrace.ixx.o \
	$(BUILD_DIR)/mtrace.cpp.o $(BUILD_DIR)/main.o
	$(CXX) $(CXXFLAGS) $^ -o $@

clean:
	rm -rf $(BUILD_DIR)
