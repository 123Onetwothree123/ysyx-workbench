SHELL := /bin/bash

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
NPC_DIR := $(abspath $(TEST_DIR)/../../..)
BUILD_DIR := $(TEST_DIR)/build
RTL_DIR := $(BUILD_DIR)/rtl
OBJ_DIR := $(BUILD_DIR)/obj_dir
RTL := $(RTL_DIR)/ysyx_26030103.sv
BIN := $(OBJ_DIR)/Vysyx_26030103_EXU

SCALA_SRCS := $(shell find $(NPC_DIR)/src -type f -name '*.scala' -print)
SCALA_SRCS += $(NPC_DIR)/build.mill $(TEST_DIR)/Makefile

VERILATOR ?= verilator
MILL ?= mill

.PHONY: all run clean

all: $(BIN)

run: $(BIN)
	$(BIN)

# Do not invoke npc/Makefile's "verilog" target here: it calls git_commit.
# Generate an isolated, deterministic RV32I RTL snapshot for this module test.
$(RTL): $(SCALA_SRCS)
	mkdir -p $(RTL_DIR)
	cd $(NPC_DIR) && env \
		ICACHE=n DCACHE=n WBUF_DEPTH=4 \
		RV32_M=y RV32_A=n RV32_C=n \
		$(MILL) --no-server runMain ysyx_26030103_Elaborate \
			--target-dir $(RTL_DIR)

$(BIN): main.cpp $(RTL)
	$(VERILATOR) --cc --exe --build --assert -Wno-fatal \
		--top-module ysyx_26030103_EXU \
		--Mdir $(OBJ_DIR) -I$(RTL_DIR) \
		-CFLAGS "-std=c++20 -O2 -Wall -Wextra" \
		-o Vysyx_26030103_EXU \
		$(RTL) $(TEST_DIR)/main.cpp

clean:
	rm -rf $(BUILD_DIR)
