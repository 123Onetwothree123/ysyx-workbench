CXX ?= g++
CXXFLAGS ?= -std=c++26 -fmodules -O2 -Wall -Wextra -Wpedantic

NPC_HOME := $(abspath ../../..)
BUILD_DIR := build
BIN := $(BUILD_DIR)/perf-stats-test
STD_MODULE_SRC := $(lastword $(wildcard /usr/include/c++/*/bits/std.cc))
PERF_MODULE_SRC := $(NPC_HOME)/csrc/PerfStats.ixx
RESULT_MODULE_SRC := $(NPC_HOME)/csrc/NPCSimResult/NPCSimResult.ixx
RESULT_IMPL_SRC := $(NPC_HOME)/csrc/NPCSimResult/NPCSimResult.cpp

.PHONY: all test clean

all: $(BIN)

test: $(BIN)
	$(BIN)

$(BUILD_DIR):
	mkdir -p $@

$(BUILD_DIR)/std.o: $(STD_MODULE_SRC) | $(BUILD_DIR)
	cd $(BUILD_DIR) && $(CXX) $(CXXFLAGS) -x c++ -c $(abspath $<) -o std.o

$(BUILD_DIR)/PerfStats.o: $(PERF_MODULE_SRC) $(BUILD_DIR)/std.o
	cd $(BUILD_DIR) && $(CXX) $(CXXFLAGS) -x c++ -c $(abspath $<) -o PerfStats.o

$(BUILD_DIR)/NPCSimResult.ixx.o: $(RESULT_MODULE_SRC) $(BUILD_DIR)/PerfStats.o
	cd $(BUILD_DIR) && $(CXX) $(CXXFLAGS) -x c++ -c $(abspath $<) -o NPCSimResult.ixx.o

$(BUILD_DIR)/NPCSimResult.cpp.o: $(RESULT_IMPL_SRC) $(BUILD_DIR)/NPCSimResult.ixx.o
	cd $(BUILD_DIR) && $(CXX) $(CXXFLAGS) -c $(abspath $<) -o NPCSimResult.cpp.o

$(BUILD_DIR)/main.o: main.cpp $(BUILD_DIR)/NPCSimResult.ixx.o
	cd $(BUILD_DIR) && $(CXX) $(CXXFLAGS) -c $(abspath $<) -o main.o

$(BIN): $(BUILD_DIR)/std.o $(BUILD_DIR)/PerfStats.o \
	$(BUILD_DIR)/NPCSimResult.ixx.o $(BUILD_DIR)/NPCSimResult.cpp.o \
	$(BUILD_DIR)/main.o
	$(CXX) $(CXXFLAGS) $^ -o $@

clean:
	rm -rf $(BUILD_DIR)
