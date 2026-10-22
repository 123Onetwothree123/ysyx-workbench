SHELL := /bin/bash

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
NPC_DIR := $(abspath $(TEST_DIR)/../../..)
BUILD_DIR := $(TEST_DIR)/build
OFF_RTL_DIR := $(BUILD_DIR)/rtl-off
ON_RTL_DIR := $(BUILD_DIR)/rtl-on
PERF_RTL_DIR := $(BUILD_DIR)/rtl-perf
FILE_RTL_DIR := $(BUILD_DIR)/rtl-itrace-file
OFF_NPC_DIR := $(BUILD_DIR)/npc-off
OFF_BIN := $(OFF_NPC_DIR)/ysyx_26030103_npc
GOOD_BIN := $(BUILD_DIR)/good.bin
BAD_BIN := $(BUILD_DIR)/bad.bin

SCALA_SOURCES := $(shell find $(NPC_DIR)/src -type f -name '*.scala' | sort)
BASE_ENV := CACHE_BLOCK_SIZE_LOG2=4 CACHE_INDEX_BITS=5 \
	ICACHE=y DCACHE=n WBUF_DEPTH=4 CACHE_PADDING=0 CHIPLINK=n \
	BTB_BITS=4 BTB_WAYS=1 JAL_BTB_BITS=4 JAL_BTB_WAYS=1 RAS_BITS=4 \
	RISCV_M=n RISCV_A=n RISCV_C=n
OBS_OFF_ENV := SDB=n DIFFTEST=n MTRACE=n ITRACE=n FTRACE=n \
	IRINGBUF=n PERF_STATS=n
OBS_ON_ENV := SDB=y DIFFTEST=y MTRACE=y ITRACE=y FTRACE=y \
	IRINGBUF=y PERF_STATS=y

OFF_RTL := $(OFF_RTL_DIR)/ysyx_26030103_npc.sv
ON_RTL := $(ON_RTL_DIR)/ysyx_26030103_npc.sv
PERF_RTL := $(PERF_RTL_DIR)/ysyx_26030103_npc.sv
FILE_RTL := $(FILE_RTL_DIR)/ysyx_26030103_npc.sv

.PHONY: all test clean

all: test

$(GOOD_BIN):
	@mkdir -p $(BUILD_DIR)
	@printf '\023\005\000\000\013\000\000\000' > $@

# addi a0,zero,7; custom-0 simulation halt.  This proves the all-off fixed
# debug read path is a real a0 tap rather than a constant zero.
$(BAD_BIN):
	@mkdir -p $(BUILD_DIR)
	@printf '\023\005\160\000\013\000\000\000' > $@

$(OFF_RTL): $(SCALA_SOURCES) Makefile
	@mkdir -p $(OFF_RTL_DIR)
	@cd $(NPC_DIR) && env $(BASE_ENV) $(OBS_OFF_ENV) \
		mill --no-server runMain ysyx_26030103_Elaborate \
		--target-dir $(OFF_RTL_DIR)

$(ON_RTL): $(SCALA_SOURCES) Makefile
	@mkdir -p $(ON_RTL_DIR)
	@cd $(NPC_DIR) && env $(BASE_ENV) $(OBS_ON_ENV) \
		mill --no-server runMain ysyx_26030103_Elaborate \
		--target-dir $(ON_RTL_DIR)

# PERF alone needs retire and trap events, but not the dynamic GPR mux, CSR
# snapshots, or mtrace payload registers.
$(PERF_RTL): $(SCALA_SOURCES) Makefile
	@mkdir -p $(PERF_RTL_DIR)
	@cd $(NPC_DIR) && env $(BASE_ENV) $(OBS_OFF_ENV) PERF_STATS=y \
		mill --no-server runMain ysyx_26030103_Elaborate \
		--target-dir $(PERF_RTL_DIR)

# The Makefile folds ITRACE_WRITE_FILE into the RTL ITRACE switch.  Exercise
# that production path rather than calling the elaborator directly.
$(FILE_RTL): $(SCALA_SOURCES) Makefile
	@$(MAKE) -C $(NPC_DIR) BUILD_DIR=$(FILE_RTL_DIR) git_commit= \
		CONFIG_SDB=n CONFIG_DIFFTEST=n CONFIG_MTRACE=n \
		CONFIG_ITRACE=n CONFIG_ITRACE_WRITE_FILE=y CONFIG_FTRACE=n \
		CONFIG_IRINGBUF=n CONFIG_PERF_STATS=n verilog

$(OFF_BIN): $(GOOD_BIN) $(BAD_BIN) $(SCALA_SOURCES) Makefile
	@$(MAKE) -C $(NPC_DIR) BUILD_DIR=$(OFF_NPC_DIR) git_commit= SIM_TOP=npc \
		CONFIG_SDB=n CONFIG_DIFFTEST=n CONFIG_MTRACE=n \
		CONFIG_ITRACE=n CONFIG_ITRACE_WRITE_FILE=n CONFIG_FTRACE=n \
		CONFIG_IRINGBUF=n CONFIG_PERF_STATS=n CONFIG_PERF_SAVE=n \
		CONFIG_WATCHPOINT=n CONFIG_NVBOARD=n CONFIG_TRACE=n $@

test: $(OFF_RTL) $(ON_RTL) $(PERF_RTL) $(FILE_RTL) $(OFF_BIN)
	@mkdir -p $(BUILD_DIR)/run
	@cd $(BUILD_DIR)/run && $(OFF_BIN) $(GOOD_BIN) > output.log 2>&1
	@grep -Fq 'HIT GOOD TRAP' $(BUILD_DIR)/run/output.log
	@cd $(BUILD_DIR)/run; set +e; \
		$(OFF_BIN) $(BAD_BIN) > bad-output.log 2>&1; \
		status=$$?; set -e; test $$status -eq 1
	@grep -Fq 'HIT BAD TRAP' $(BUILD_DIR)/run/bad-output.log
	@grep -Fq 'code = 7' $(BUILD_DIR)/run/bad-output.log
	@# Fixed core ABI: configuration changes implementation, never port names.
	@sed -n '/^module ysyx_26030103(/,/^);/p' $(OFF_RTL) > $(BUILD_DIR)/off.ports
	@sed -n '/^module ysyx_26030103(/,/^);/p' $(ON_RTL) > $(BUILD_DIR)/on.ports
	@cmp $(BUILD_DIR)/off.ports $(BUILD_DIR)/on.ports
	@# All-off keeps only custom-halt state and the architectural PC.  Every
	@# optional snapshot register must be absent from the emitted RTL.
	@awk '/^module ysyx_26030103\(/,/^endmodule/' $(OFF_RTL) \
		> $(BUILD_DIR)/off-core.sv
	@! grep -Eq '^  reg .*io_debug_' $(BUILD_DIR)/off-core.sv
	@grep -Fq 'reg  [31:0] ArchitecturalPC' $(OFF_RTL)
	@awk '/^module ysyx_26030103_RegisterFile\(/,/^endmodule/' $(OFF_RTL) \
		> $(BUILD_DIR)/off-register-file.sv
	@! grep -q 'io_debug_raddr' $(BUILD_DIR)/off-register-file.sv
	@grep -Eq 'assign io_debug_rdata = ysyx_26030103_RegisterFile_10' \
		$(BUILD_DIR)/off-register-file.sv
	@# The enabled image proves the negative greps are not vacuous.
	@grep -q 'io_debug_commit_REG' $(ON_RTL)
	@grep -q 'io_debug_csr_mstatus_r' $(ON_RTL)
	@grep -q 'io_debug_mtrace_addr_r' $(ON_RTL)
	@awk '/^module ysyx_26030103_RegisterFile\(/,/^endmodule/' $(ON_RTL) | \
		grep -q 'io_debug_raddr'
	@# PERF-only must retain registered trap accounting while still omitting
	@# DiffTest-only CSR state and the third dynamic GPR read port.
	@grep -q 'io_debug_commit_REG' $(PERF_RTL)
	@grep -q 'io_debug_trap_valid_REG' $(PERF_RTL)
	@! grep -q 'io_debug_csr_mstatus_r' $(PERF_RTL)
	@! awk '/^module ysyx_26030103_RegisterFile\(/,/^endmodule/' $(PERF_RTL) | \
		grep -q 'io_debug_raddr'
	@# File-only itrace is still a retire-trace consumer.
	@grep -q 'io_debug_commit_REG' $(FILE_RTL)
	@# Quantify the post-flattening hardware reduction, not just source names.
	@yosys -q -p 'read_verilog -sv -DSYNTHESIS $(OFF_RTL); hierarchy -top ysyx_26030103; proc; flatten; opt; tee -o $(BUILD_DIR)/off-stat.json stat -json'
	@yosys -q -p 'read_verilog -sv -DSYNTHESIS $(ON_RTL); hierarchy -top ysyx_26030103; proc; flatten; opt; tee -o $(BUILD_DIR)/on-stat.json stat -json'
	@off_cells=$$(sed -n 's/.*"num_cells":[[:space:]]*\([0-9][0-9]*\).*/\1/p' $(BUILD_DIR)/off-stat.json | head -1); \
	 on_cells=$$(sed -n 's/.*"num_cells":[[:space:]]*\([0-9][0-9]*\).*/\1/p' $(BUILD_DIR)/on-stat.json | head -1); \
	 test -n "$$off_cells"; test -n "$$on_cells"; \
	 test "$$off_cells" -lt "$$on_cells"; \
	 echo "[PASS] observability gate: all-off $$off_cells cells < all-on $$on_cells cells"

clean:
	rm -rf $(BUILD_DIR)
