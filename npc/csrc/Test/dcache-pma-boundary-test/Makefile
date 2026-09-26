SHELL := /bin/bash

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
BUILD_DIR := $(TEST_DIR)/build
RTL_DIR := $(BUILD_DIR)/rtl
OBJ_DIR := $(BUILD_DIR)/obj
BIN := $(BUILD_DIR)/dcache_pma_boundary_test
MILL ?= mill
VERILATOR ?= verilator

SCALA_SOURCES := $(shell find $(TEST_DIR)/scala -type f -name '*.scala' | sort)
SCALA_SOURCES += $(TEST_DIR)/build.mill
SCALA_SOURCES += $(wildcard $(TEST_DIR)/../../../src/Common/*.scala)
SCALA_SOURCES += $(wildcard $(TEST_DIR)/../../../src/Infra/*.scala)
SCALA_SOURCES += $(TEST_DIR)/../../../src/MEM/ysyx_26030103_DCache.scala
STAMP := $(RTL_DIR)/.generated

.PHONY: all test run clean

all: $(BIN)

test run: $(BIN)
	$(BIN)

$(STAMP): $(SCALA_SOURCES) Makefile \
		layers-DCachePmaLineBoundaryHarness-Verification.sv
	mkdir -p $(RTL_DIR)
	cd $(TEST_DIR) && $(MILL) --no-server runMain \
		ysyx_26030103.test.DCachePmaLineBoundaryHarnessElaborate \
		--target-dir $(RTL_DIR)
	cp $(TEST_DIR)/layers-DCachePmaLineBoundaryHarness-Verification.sv \
		$(RTL_DIR)/
	touch $(STAMP)

$(BIN): main.cpp test_common.hpp $(STAMP)
	$(VERILATOR) --cc --exe --build -j 0 -Wall -Wno-fatal -DSYNTHESIS \
		-I$(RTL_DIR) \
		--top-module DCachePmaLineBoundaryHarness \
		--Mdir $(OBJ_DIR) \
		-CFLAGS '-std=c++20 -O2 -Wall -Wextra -I$(TEST_DIR)' \
		-o $(abspath $@) \
		$(RTL_DIR)/DCachePmaLineBoundaryHarness.sv main.cpp

clean:
	rm -rf $(BUILD_DIR) out
