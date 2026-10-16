CXX ?= g++
CXXFLAGS ?= -std=c++26 -fmodules -O2 -Wall -Wextra -Wpedantic
SHELL := /bin/bash

NPC_HOME := $(abspath ../../..)
BUILD_DIR := build
BIN_BASE := $(BUILD_DIR)/perf-stats-test-base
BIN_ALL := $(BUILD_DIR)/perf-stats-test-all-features
STD_MODULE_SRC := $(lastword $(wildcard /usr/include/c++/*/bits/std.cc))
PERF_MODULE_SRC := $(NPC_HOME)/csrc/PerfStats.ixx
RESULT_MODULE_SRC := $(NPC_HOME)/csrc/NPCSimResult/NPCSimResult.ixx
RESULT_IMPL_SRC := $(NPC_HOME)/csrc/NPCSimResult/NPCSimResult.cpp
METADATA_DIR := $(abspath $(BUILD_DIR)/custom-metadata)
BUILD_METADATA_FILE := $(METADATA_DIR)/perf_build_metadata.txt
SYNTH_FILE := $(METADATA_DIR)/synth.txt
FAKE_YOSYS_HOME := $(abspath fake-yosys)
FAKE_VERILATOR_BUILD := $(abspath fake-verilator-build.mk)
SYNTH_ISOLATION_A := $(abspath $(BUILD_DIR)/isolation-a)
SYNTH_ISOLATION_B := $(abspath $(BUILD_DIR)/isolation-b)
SYNTH_MISSING_STAT := $(abspath $(BUILD_DIR)/missing-stat)
SYNTH_FAILURE := $(abspath $(BUILD_DIR)/synth-failure)
PERF_FORCE_LOG := $(abspath $(BUILD_DIR)/perf-force-rebuild.log)
PERF_ORDER_LOG := $(abspath $(BUILD_DIR)/perf-ordering.log)
RESULT_CPPFLAGS := \
	-DCONFIG_PERF_BUILD_METADATA_FILE='"$(BUILD_METADATA_FILE)"' \
	-DCONFIG_PERF_SYNTH_FILE='"$(SYNTH_FILE)"' \
	-DCONFIG_PDK=testpdk

.PHONY: all test prepare-fixtures synth-isolation-test synth-missing-stat-test \
	synth-failure-test perf-ordering-test clean

all: $(BIN_BASE) $(BIN_ALL)

test: prepare-fixtures perf-ordering-test synth-missing-stat-test \
	synth-failure-test \
	$(BIN_BASE) $(BIN_ALL)
	rm -rf $(BUILD_DIR)/csv-result-base $(BUILD_DIR)/csv-result-all \
	  $(BUILD_DIR)/csv-result-stale $(BUILD_DIR)/csv-result-unmeasured
	$(BIN_BASE) valid $(BUILD_DIR)/csv-result-base soc
	printf '%s\n' \
	  'format=2' 'target=npc' 'pdk=testpdk' \
	  'config_sha256=config-current' 'rtl_sha256=npc-rtl-current' \
	  'measurement_valid=1' \
	  'frequency_mhz=777' 'area_um2=123.5' > $(SYNTH_FILE)
	$(BIN_ALL) valid $(BUILD_DIR)/csv-result-all npc
	cmp <(head -n 1 $(BUILD_DIR)/csv-result-base/result_ysyxsoc_testpdk.csv) \
	    <(head -n 1 $(BUILD_DIR)/csv-result-all/result_npc_testpdk.csv)
	printf '%s\n' \
	  'format=2' 'target=soc' 'pdk=testpdk' \
	  'config_sha256=config-current' 'rtl_sha256=rtl-stale' \
	  'measurement_valid=1' \
	  'frequency_mhz=999' 'area_um2=999' > $(SYNTH_FILE)
	$(BIN_BASE) stale $(BUILD_DIR)/csv-result-stale soc
	printf '%s\n' \
	  'format=2' 'target=soc' 'pdk=testpdk' \
	  'config_sha256=config-current' 'rtl_sha256=rtl-current' \
	  'frequency_mhz=777' 'area_um2=0' > $(SYNTH_FILE)
	$(BIN_BASE) stale $(BUILD_DIR)/csv-result-unmeasured soc

# Run two synthesis targets at the same time with the same design/frequency/PDK
# but different BUILD_DIR and RTL.  A shared yosys-sta/result directory used to
# let one invocation stamp the other invocation's area with its own RTL hash.
synth-isolation-test:
	rm -rf $(SYNTH_ISOLATION_A) $(SYNTH_ISOLATION_B)
	mkdir -p $(SYNTH_ISOLATION_A) $(SYNTH_ISOLATION_B)
	printf 'module npc_a; endmodule\n' > $(SYNTH_ISOLATION_A)/ysyx_26030103_npc.sv
	printf 'module soc_a; endmodule\n' > $(SYNTH_ISOLATION_A)/ysyx_26030103.sv
	printf 'module npc_b; endmodule\n' > $(SYNTH_ISOLATION_B)/ysyx_26030103_npc.sv
	printf 'module soc_b; endmodule\n' > $(SYNTH_ISOLATION_B)/ysyx_26030103.sv
	@set -eu; \
	$(MAKE) -C $(NPC_HOME) -o verilog synth git_commit= \
	  BUILD_DIR=$(SYNTH_ISOLATION_A) YOSYS_STA_HOME=$(FAKE_YOSYS_HOME) \
	  SIM_TOP=npc > $(SYNTH_ISOLATION_A)/run.log 2>&1 & pid_a=$$!; \
	$(MAKE) -C $(NPC_HOME) -o verilog synth git_commit= \
	  BUILD_DIR=$(SYNTH_ISOLATION_B) YOSYS_STA_HOME=$(FAKE_YOSYS_HOME) \
	  SIM_TOP=npc > $(SYNTH_ISOLATION_B)/run.log 2>&1 & pid_b=$$!; \
	status=0; \
	wait $$pid_a || status=1; \
	wait $$pid_b || status=1; \
	if test $$status -ne 0; then \
	  cat $(SYNTH_ISOLATION_A)/run.log $(SYNTH_ISOLATION_B)/run.log; \
	  exit $$status; \
	fi
	grep -Fxq 'area_um2=111.1' $(SYNTH_ISOLATION_A)/synth.txt
	grep -Fxq 'area_um2=222.2' $(SYNTH_ISOLATION_B)/synth.txt
	test "$$(find $(SYNTH_ISOLATION_A)/yosys-sta -name synth_stat.txt | wc -l)" -eq 1
	test "$$(find $(SYNTH_ISOLATION_B)/yosys-sta -name synth_stat.txt | wc -l)" -eq 1
	@a_config=$$(sed -n 's/^config_sha256=//p' $(SYNTH_ISOLATION_A)/synth.txt); \
	a_rtl=$$(sed -n 's/^rtl_sha256=//p' $(SYNTH_ISOLATION_A)/synth.txt); \
	b_config=$$(sed -n 's/^config_sha256=//p' $(SYNTH_ISOLATION_B)/synth.txt); \
	b_rtl=$$(sed -n 's/^rtl_sha256=//p' $(SYNTH_ISOLATION_B)/synth.txt); \
	test -d "$(SYNTH_ISOLATION_A)/yosys-sta/npc-$$a_config-$$a_rtl"; \
	test -d "$(SYNTH_ISOLATION_B)/yosys-sta/npc-$$b_config-$$b_rtl"; \
	test "$$a_rtl" != "$$b_rtl"
	test ! -e $(FAKE_YOSYS_HOME)/result

# Reusing an identical build identity must not reuse its old area report.  Run
# once with a valid report, then make the next successful synthesis omit it.
# The second manifest must be invalid and the old report must be gone.
synth-missing-stat-test:
	rm -rf $(SYNTH_MISSING_STAT)
	mkdir -p $(SYNTH_MISSING_STAT)
	printf 'module npc_missing; endmodule\n' > $(SYNTH_MISSING_STAT)/ysyx_26030103_npc.sv
	printf 'module soc_missing; endmodule\n' > $(SYNTH_MISSING_STAT)/ysyx_26030103.sv
	$(MAKE) -C $(NPC_HOME) -o verilog synth git_commit= \
	  BUILD_DIR=$(SYNTH_MISSING_STAT) YOSYS_STA_HOME=$(FAKE_YOSYS_HOME) \
	  SIM_TOP=npc
	grep -Fxq 'measurement_valid=1' $(SYNTH_MISSING_STAT)/synth.txt
	grep -Fxq 'area_um2=333.3' $(SYNTH_MISSING_STAT)/synth.txt
	test "$$(find $(SYNTH_MISSING_STAT)/yosys-sta -name synth_stat.txt | wc -l)" -eq 1
	$(MAKE) -C $(NPC_HOME) -o verilog synth git_commit= \
	  BUILD_DIR=$(SYNTH_MISSING_STAT) YOSYS_STA_HOME=$(FAKE_YOSYS_HOME) \
	  FAKE_YOSYS_SKIP_STAT=1 SIM_TOP=npc
	grep -Fxq 'measurement_valid=0' $(SYNTH_MISSING_STAT)/synth.txt
	grep -Fxq 'area_um2=0' $(SYNTH_MISSING_STAT)/synth.txt
	test "$$(find $(SYNTH_MISSING_STAT)/yosys-sta -name synth_stat.txt | wc -l)" -eq 0

# A failed synthesis must invalidate the manifest before entering yosys-sta.
# Start from a valid result with the same identity, then make yosys-sta fail;
# neither that old manifest nor its old area report may remain usable.
synth-failure-test:
	rm -rf $(SYNTH_FAILURE)
	mkdir -p $(SYNTH_FAILURE)
	printf 'module npc_failure; endmodule\n' > $(SYNTH_FAILURE)/ysyx_26030103_npc.sv
	printf 'module soc_failure; endmodule\n' > $(SYNTH_FAILURE)/ysyx_26030103.sv
	$(MAKE) -C $(NPC_HOME) -o verilog synth git_commit= \
	  BUILD_DIR=$(SYNTH_FAILURE) YOSYS_STA_HOME=$(FAKE_YOSYS_HOME) \
	  SIM_TOP=npc
	grep -Fxq 'measurement_valid=1' $(SYNTH_FAILURE)/synth.txt
	grep -Fxq 'area_um2=444.4' $(SYNTH_FAILURE)/synth.txt
	@test "$$(find $(SYNTH_FAILURE)/yosys-sta -name synth_stat.txt | wc -l)" -eq 1
	@if $(MAKE) -C $(NPC_HOME) -o verilog synth git_commit= \
	  BUILD_DIR=$(SYNTH_FAILURE) YOSYS_STA_HOME=$(FAKE_YOSYS_HOME) \
	  FAKE_YOSYS_FAIL=1 SIM_TOP=npc; then \
	  echo 'expected fake synthesis to fail' >&2; \
	  exit 1; \
	fi
	grep -Fxq 'measurement_valid=0' $(SYNTH_FAILURE)/synth.txt
	grep -Fxq 'area_um2=0' $(SYNTH_FAILURE)/synth.txt
	test "$$(find $(SYNTH_FAILURE)/yosys-sta -name synth_stat.txt | wc -l)" -eq 0

# Even if an existing simulator is newer than every C++ source, the ordered
# post-synthesis sub-make must rerun Verilator for the just-synthesized RTL.
# It must do so without scheduling a second Chisel elaboration or metadata run.
perf-ordering-test: synth-isolation-test
	touch $(SYNTH_ISOLATION_A)/ysyx_26030103_npc
	mkdir -p $(SYNTH_ISOLATION_A)/obj_dir_ysyx_26030103_npc
	$(MAKE) -C $(NPC_HOME) -n git_commit= PERF_SYNTH_READY=1 \
	  BUILD_DIR=$(SYNTH_ISOLATION_A) VERILATOR_BUILD_WRAPPER=$(FAKE_VERILATOR_BUILD) \
	  $(SYNTH_ISOLATION_A)/ysyx_26030103_npc > $(PERF_FORCE_LOG)
	test "$$(grep -c '^verilator ' $(PERF_FORCE_LOG))" -eq 1
	test "$$(grep -c 'runMain ysyx_26030103_Elaborate' $(PERF_FORCE_LOG))" -eq 0
	$(MAKE) -C $(NPC_HOME) -n -j4 perf git_commit= \
	  BUILD_DIR=$(SYNTH_ISOLATION_A) YOSYS_STA_HOME=$(FAKE_YOSYS_HOME) \
	  VERILATOR_BUILD_WRAPPER=$(FAKE_VERILATOR_BUILD) \
	  SIM_TOP=npc file=/tmp/perf-ordering-guest.bin > $(PERF_ORDER_LOG)
	test "$$(grep -c 'runMain ysyx_26030103_Elaborate' $(PERF_ORDER_LOG))" -eq 1
	test "$$(grep -c 'tmp=".*/perf_build_metadata.txt.tmp' $(PERF_ORDER_LOG))" -eq 1
	test "$$(grep -c '^verilator ' $(PERF_ORDER_LOG))" -eq 1

$(BUILD_DIR):
	mkdir -p $@

$(BUILD_DIR)/std.o: $(STD_MODULE_SRC) | $(BUILD_DIR)
	cd $(BUILD_DIR) && $(CXX) $(CXXFLAGS) -x c++ -c $(abspath $<) -o std.o

$(BUILD_DIR)/PerfStats.o: $(PERF_MODULE_SRC) $(BUILD_DIR)/std.o
	cd $(BUILD_DIR) && $(CXX) $(CXXFLAGS) -x c++ -c $(abspath $<) -o PerfStats.o

$(BUILD_DIR)/NPCSimResult.ixx.o: $(RESULT_MODULE_SRC) $(BUILD_DIR)/PerfStats.o
	cd $(BUILD_DIR) && $(CXX) $(CXXFLAGS) -x c++ -c $(abspath $<) -o NPCSimResult.ixx.o

$(BUILD_DIR)/NPCSimResult.base.o: $(RESULT_IMPL_SRC) $(BUILD_DIR)/NPCSimResult.ixx.o
	cd $(BUILD_DIR) && $(CXX) $(CXXFLAGS) $(RESULT_CPPFLAGS) -c $(abspath $<) -o NPCSimResult.base.o

$(BUILD_DIR)/NPCSimResult.all.o: $(RESULT_IMPL_SRC) $(BUILD_DIR)/NPCSimResult.ixx.o
	cd $(BUILD_DIR) && $(CXX) $(CXXFLAGS) $(RESULT_CPPFLAGS) \
	  -DCONFIG_ICACHE -DCONFIG_DCACHE -DCONFIG_RV32_M -DVRISCV32E_NPC \
	  -c $(abspath $<) -o NPCSimResult.all.o

$(BUILD_DIR)/main.o: main.cpp $(BUILD_DIR)/NPCSimResult.ixx.o
	cd $(BUILD_DIR) && $(CXX) $(CXXFLAGS) -c $(abspath $<) -o main.o

$(METADATA_DIR): | $(BUILD_DIR)
	mkdir -p $(METADATA_DIR)

prepare-fixtures: | $(METADATA_DIR)
	printf '%s\n' \
	  'format=1' 'commit=abc123def456' 'git_dirty=1' \
	  'message=custom,metadata "message"' \
	  'config_sha256=config-current' \
	  'config_summary=ICACHE=n;DCACHE=n;MUL_OFFSETS=0,1' \
	  'rtl_npc_sha256=npc-rtl-current' \
	  'rtl_soc_sha256=rtl-current' 'pdk=testpdk' > $(BUILD_METADATA_FILE)
	printf '%s\n' \
	  'format=2' 'target=soc' 'pdk=testpdk' \
	  'config_sha256=config-current' 'rtl_sha256=rtl-current' \
	  'measurement_valid=1' \
	  'frequency_mhz=777' 'area_um2=123.5' > $(SYNTH_FILE)

$(BIN_BASE): $(BUILD_DIR)/std.o $(BUILD_DIR)/PerfStats.o \
	$(BUILD_DIR)/NPCSimResult.ixx.o $(BUILD_DIR)/NPCSimResult.base.o \
	$(BUILD_DIR)/main.o
	$(CXX) $(CXXFLAGS) $^ -o $@

$(BIN_ALL): $(BUILD_DIR)/std.o $(BUILD_DIR)/PerfStats.o \
	$(BUILD_DIR)/NPCSimResult.ixx.o $(BUILD_DIR)/NPCSimResult.all.o \
	$(BUILD_DIR)/main.o
	$(CXX) $(CXXFLAGS) $^ -o $@

clean:
	rm -rf $(BUILD_DIR)
