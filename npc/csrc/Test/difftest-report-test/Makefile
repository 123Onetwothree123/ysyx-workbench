SHELL := /bin/bash
TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
BUILD_DIR := $(TEST_DIR)/build
CMAKE_BUILD_DIR := $(BUILD_DIR)/cmake
STAMP := $(CMAKE_BUILD_DIR)/.built
TEST_BIN := $(BUILD_DIR)/difftest-report-test
MODULE := $(TEST_DIR)/../../difftest/DifftestReport.ixx
HOST_CXX ?= g++

.PHONY: all run clean

all: $(TEST_BIN)

$(STAMP): main.cpp $(MODULE) CMakeLists.txt
	cmake -S "$(TEST_DIR)" -B "$(CMAKE_BUILD_DIR)" -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_COMPILER="$(HOST_CXX)"
	cmake --build "$(CMAKE_BUILD_DIR)"
	@touch $@

$(TEST_BIN): $(STAMP)
	@test -x "$@" || { echo "missing $@" >&2; exit 1; }

run: $(TEST_BIN)
	$(TEST_BIN)

clean:
	rm -rf $(BUILD_DIR)
