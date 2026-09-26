CXX ?= g++
CXXFLAGS ?= -std=c++26 -fmodules -O2 -Wall -Wextra -Werror

NPC_DIR := $(abspath ../../..)
BUILD_DIR := $(CURDIR)/build
BIN := $(BUILD_DIR)/ftrace-test
STD_MODULE_SRC := $(lastword $(wildcard /usr/include/c++/*/bits/std.cc))

.PHONY: all test clean

all: $(BIN)

test: $(BIN)
	$(BIN)

$(BUILD_DIR):
	mkdir -p $@

$(BUILD_DIR)/std.o: $(STD_MODULE_SRC) | $(BUILD_DIR)
	cd $(BUILD_DIR) && $(CXX) $(CXXFLAGS) -x c++ -c $(abspath $<) -o std.o

$(BUILD_DIR)/unicode.o: $(NPC_DIR)/csrc/unicode.ixx $(BUILD_DIR)/std.o
	cd $(BUILD_DIR) && $(CXX) $(CXXFLAGS) -x c++ -c $(abspath $<) -o unicode.o

$(BUILD_DIR)/ReadelfFunction.ixx.o: $(NPC_DIR)/csrc/trace/ReadelfFunction.ixx $(BUILD_DIR)/std.o
	cd $(BUILD_DIR) && $(CXX) $(CXXFLAGS) -x c++ -c $(abspath $<) -o ReadelfFunction.ixx.o

$(BUILD_DIR)/ReadelfFunction.cpp.o: $(NPC_DIR)/csrc/trace/ReadelfFunction.cpp $(BUILD_DIR)/ReadelfFunction.ixx.o
	cd $(BUILD_DIR) && $(CXX) $(CXXFLAGS) -c $(abspath $<) -o ReadelfFunction.cpp.o

$(BUILD_DIR)/readelf.ixx.o: $(NPC_DIR)/csrc/trace/readelf.ixx $(BUILD_DIR)/ReadelfFunction.ixx.o
	cd $(BUILD_DIR) && $(CXX) $(CXXFLAGS) -x c++ -c $(abspath $<) -o readelf.ixx.o

$(BUILD_DIR)/readelf.cpp.o: $(NPC_DIR)/csrc/trace/readelf.cpp $(BUILD_DIR)/readelf.ixx.o $(BUILD_DIR)/unicode.o
	cd $(BUILD_DIR) && $(CXX) $(CXXFLAGS) -c $(abspath $<) -o readelf.cpp.o

$(BUILD_DIR)/FtraceEvent.ixx.o: $(NPC_DIR)/csrc/trace/FtraceEvent.ixx $(BUILD_DIR)/std.o
	cd $(BUILD_DIR) && $(CXX) $(CXXFLAGS) -x c++ -c $(abspath $<) -o FtraceEvent.ixx.o

$(BUILD_DIR)/FtraceEvent.cpp.o: $(NPC_DIR)/csrc/trace/FtraceEvent.cpp $(BUILD_DIR)/FtraceEvent.ixx.o
	cd $(BUILD_DIR) && $(CXX) $(CXXFLAGS) -c $(abspath $<) -o FtraceEvent.cpp.o

$(BUILD_DIR)/FtraceFrame.ixx.o: $(NPC_DIR)/csrc/trace/FtraceFrame.ixx $(BUILD_DIR)/std.o
	cd $(BUILD_DIR) && $(CXX) $(CXXFLAGS) -x c++ -c $(abspath $<) -o FtraceFrame.ixx.o

$(BUILD_DIR)/FtraceFrame.cpp.o: $(NPC_DIR)/csrc/trace/FtraceFrame.cpp $(BUILD_DIR)/FtraceFrame.ixx.o
	cd $(BUILD_DIR) && $(CXX) $(CXXFLAGS) -c $(abspath $<) -o FtraceFrame.cpp.o

$(BUILD_DIR)/ftrace.ixx.o: $(NPC_DIR)/csrc/trace/ftrace.ixx $(BUILD_DIR)/FtraceEvent.ixx.o $(BUILD_DIR)/FtraceFrame.ixx.o $(BUILD_DIR)/readelf.ixx.o
	cd $(BUILD_DIR) && $(CXX) $(CXXFLAGS) -x c++ -c $(abspath $<) -o ftrace.ixx.o

$(BUILD_DIR)/ftrace.cpp.o: $(NPC_DIR)/csrc/trace/ftrace.cpp $(BUILD_DIR)/ftrace.ixx.o
	cd $(BUILD_DIR) && $(CXX) $(CXXFLAGS) -c $(abspath $<) -o ftrace.cpp.o

$(BUILD_DIR)/main.o: main.cpp $(BUILD_DIR)/ftrace.ixx.o
	cd $(BUILD_DIR) && $(CXX) $(CXXFLAGS) -c $(abspath $<) -o main.o

$(BIN): $(BUILD_DIR)/std.o $(BUILD_DIR)/unicode.o \
	$(BUILD_DIR)/ReadelfFunction.ixx.o $(BUILD_DIR)/ReadelfFunction.cpp.o \
	$(BUILD_DIR)/readelf.ixx.o $(BUILD_DIR)/readelf.cpp.o \
	$(BUILD_DIR)/FtraceEvent.ixx.o $(BUILD_DIR)/FtraceEvent.cpp.o \
	$(BUILD_DIR)/FtraceFrame.ixx.o $(BUILD_DIR)/FtraceFrame.cpp.o \
	$(BUILD_DIR)/ftrace.ixx.o $(BUILD_DIR)/ftrace.cpp.o $(BUILD_DIR)/main.o
	$(CXX) $(CXXFLAGS) $^ -o $@

clean:
	rm -rf $(BUILD_DIR)
