SHELL := /bin/bash

ROOT_DIR := $(abspath ../../../..)
NPC_DIR := $(ROOT_DIR)/npc
SOC_DIR := $(ROOT_DIR)/ysyxSoC
SOC_RTL := $(SOC_DIR)/build/ysyxSoCFull.v
SOC_CONFIG_JSON := $(NPC_DIR)/build/generated/npc_config.typed.json
BUILD_DIR := $(CURDIR)/build
MROM_RTL := $(BUILD_DIR)/AXI4MROM.sv
DELAYER_RTL := $(BUILD_DIR)/AXI4DelayerChisel.sv
MROM_BIN := $(BUILD_DIR)/mrom_backpressure_test
DELAYER_BIN := $(BUILD_DIR)/axi4_delayer_test
SOC_SCALA := $(shell find $(SOC_DIR)/src -type f -name '*.scala' | sort)


VERILATOR ?= verilator
CXXFLAGS := -std=c++20 -O2 -Wall -Wextra
VERILATOR_FLAGS := --cc --exe --build -j 0 -Wall -Wno-fatal

.PHONY: all test run clean

all: $(MROM_BIN) $(DELAYER_BIN)

test run: all
	@$(MROM_BIN)
	@$(DELAYER_BIN)

$(SOC_CONFIG_JSON):
	$(MAKE) -C $(NPC_DIR) npc-config

$(SOC_RTL): $(SOC_SCALA) $(SOC_CONFIG_JSON)
	$(MAKE) -C $(SOC_DIR) NPC_CONFIG_JSON=$(SOC_CONFIG_JSON) verilog

$(MROM_RTL): $(SOC_RTL)
	@mkdir -p $(@D)
	@sed -n \
	  -e '/^module AXI4MROM(/,/^endmodule/p' \
	  -e '/^module MROMHelper(/,/^endmodule/p' \
	  $< > $@

$(DELAYER_RTL): $(SOC_RTL)
	@mkdir -p $(@D)
	@sed -n \
	  -e '/^module AXI4DelayerChisel(/,/^endmodule/p' \
	  $< > $@

$(MROM_BIN): mrom_backpressure_test.cpp test_common.hpp $(MROM_RTL)
	$(VERILATOR) $(VERILATOR_FLAGS) \
	  --top-module AXI4MROM \
	  --Mdir $(BUILD_DIR)/obj-mrom \
	  -CFLAGS '$(CXXFLAGS) -I$(CURDIR)' \
	  -o $(abspath $@) \
	  $(MROM_RTL) mrom_backpressure_test.cpp

$(DELAYER_BIN): axi4_delayer_test.cpp test_common.hpp $(DELAYER_RTL)
	$(VERILATOR) $(VERILATOR_FLAGS) \
	  --top-module AXI4DelayerChisel \
	  --Mdir $(BUILD_DIR)/obj-delayer \
	  -CFLAGS '$(CXXFLAGS) -I$(CURDIR)' \
	  -o $(abspath $@) \
	  $(DELAYER_RTL) axi4_delayer_test.cpp

clean:
	rm -rf $(BUILD_DIR)
