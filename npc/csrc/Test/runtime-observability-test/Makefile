SHELL := /bin/bash

NPC_DIR := $(abspath ../../..)
BUILD_DIR := $(CURDIR)/build
NPC_BUILD_DIR := $(BUILD_DIR)/npc-build
NPC_BIN := $(NPC_BUILD_DIR)/ysyx_26030103_npc
SOC_BUILD_DIR := $(BUILD_DIR)/soc-build
SOC_BIN := $(SOC_BUILD_DIR)/ysyx_26030103
GOOD_BIN := $(BUILD_DIR)/good.bin
MEMORY_BIN := $(BUILD_DIR)/memory.bin
ILLEGAL_BIN := $(BUILD_DIR)/illegal.bin

COMMON_CONFIG := BUILD_DIR=$(NPC_BUILD_DIR) git_commit= \
	CONFIG_NVBOARD=n CONFIG_DIFFTEST=n CONFIG_PERF_STATS=n CONFIG_PERF_SAVE=n \
	CONFIG_LOG_LEVEL=0 CONFIG_LOG_TO_FILE=n CONFIG_FTRACE=n CONFIG_IRINGBUF=n \
	CONFIG_WATCHPOINT=n

.PHONY: all test clean

all: test

$(BUILD_DIR):
	mkdir -p $@

# addi a0,zero,0; custom-0 simulation halt
$(GOOD_BIN): | $(BUILD_DIR)
	printf '\023\005\000\000\013\000\000\000' > $@

# Store 42 to 0x80000100, load it back, clear a0, then halt.
# The address is beyond this 28-byte image and therefore also exercises the
# direct-NPC RAM tail which InitializeMemory deterministically zero-fills.
$(MEMORY_BIN): | $(BUILD_DIR)
	printf '\267\002\000\200\223\202\002\020\023\003\240\002\043\240\142\000\203\243\002\000\023\005\000\000\013\000\000\000' > $@

# 0x00000000 is an illegal instruction.  It takes an exception without ever
# asserting debug_commit, which used to leave `si 1` spinning forever.
$(ILLEGAL_BIN): | $(BUILD_DIR)
	printf '\000\000\000\000' > $@

test: $(GOOD_BIN) $(MEMORY_BIN) $(ILLEGAL_BIN)
	# Console itrace and mtrace: destinations and records must be unambiguous.
	$(MAKE) -C $(NPC_DIR) $(COMMON_CONFIG) CONFIG_SDB=n CONFIG_TRACE=y \
		CONFIG_ITRACE=y CONFIG_ITRACE_WRITE_FILE=n CONFIG_MTRACE=y $(NPC_BIN)
	# A non-SDB simulator must not contain the retired-store unordered_map
	# machinery at all: performance runs cannot pay per-store hash/allocation
	# overhead for a debugger that is not present.
	! nm -C $(NPC_BUILD_DIR)/obj_dir_ysyx_26030103_npc/DUT.o | \
		grep -Eq 'unordered_map|_Hashtable'
	rm -rf $(BUILD_DIR)/console-run
	mkdir -p $(BUILD_DIR)/console-run
	cd $(BUILD_DIR)/console-run && $(NPC_BIN) $(abspath $(MEMORY_BIN)) > output.log 2>&1
	test "$$(grep -c '^\[ITRACE\]' $(BUILD_DIR)/console-run/output.log)" -eq 6
	grep -Eq '^\[ITRACE\] 0x80000000: 800002b7  +lui' $(BUILD_DIR)/console-run/output.log
	test "$$(grep -c '^Memory ' $(BUILD_DIR)/console-run/output.log)" -eq 2
	test "$$(grep -c 'Width=4B' $(BUILD_DIR)/console-run/output.log)" -eq 2
	! grep -q '^\[MTRACE\]' $(BUILD_DIR)/console-run/output.log
	test ! -e $(BUILD_DIR)/console-run/itrace.txt

	# File mode is exclusive: keep the PC-only format required by cachesim and
	# do not flood stdout with the human-readable console trace.
	$(MAKE) -C $(NPC_DIR) $(COMMON_CONFIG) CONFIG_SDB=n CONFIG_TRACE=y \
		CONFIG_ITRACE=y CONFIG_ITRACE_WRITE_FILE=y CONFIG_MTRACE=n $(NPC_BIN)
	rm -rf $(BUILD_DIR)/file-run
	mkdir -p $(BUILD_DIR)/file-run
	cd $(BUILD_DIR)/file-run && $(NPC_BIN) $(abspath $(GOOD_BIN)) > output.log 2>&1
	! grep -q '^\[ITRACE\]' $(BUILD_DIR)/file-run/output.log
	test "$$(wc -l < $(BUILD_DIR)/file-run/itrace.txt)" -eq 1
	grep -Fxq '0x80000000' $(BUILD_DIR)/file-run/itrace.txt

	# SDB reads the known-zero direct RAM tail, then the retired store shadow
	# takes priority over that zero baseline.
	$(MAKE) -C $(NPC_DIR) $(COMMON_CONFIG) CONFIG_SDB=y CONFIG_TRACE=n \
		CONFIG_ITRACE=n CONFIG_ITRACE_WRITE_FILE=n CONFIG_MTRACE=n $(NPC_BIN)
	rm -rf $(BUILD_DIR)/sdb-run
	mkdir -p $(BUILD_DIR)/sdb-run
	printf 'c\nx 1w 0x80000100\nx 1w 0x80000104\nq\n' | \
		$(NPC_BIN) $(abspath $(MEMORY_BIN)) > $(BUILD_DIR)/sdb-run/output.log 2>&1
	grep -Fq '0x80000100: 0x0000002a' $(BUILD_DIR)/sdb-run/output.log
	grep -Fq '0x80000104: 0x00000000' $(BUILD_DIR)/sdb-run/output.log
	grep -Fq 'HIT GOOD TRAP' $(BUILD_DIR)/sdb-run/output.log

	# history accepts only a complete positive decimal count.  In particular,
	# from_chars must not silently accept a numeric prefix followed by garbage.
	rm -rf $(BUILD_DIR)/sdb-history-run
	mkdir -p $(BUILD_DIR)/sdb-history-run
	printf 'history 1junk\nhistory 1\nq\n' | \
		$(NPC_BIN) $(abspath $(GOOD_BIN)) > $(BUILD_DIR)/sdb-history-run/output.log 2>&1
	grep -Fq "无效参数 '1junk'。用法是 history [N]" \
		$(BUILD_DIR)/sdb-history-run/output.log
	grep -Fq '命令历史（显示最近 1 条，共 2 条）：' \
		$(BUILD_DIR)/sdb-history-run/output.log
	grep -Eq '^[[:space:]]*2[[:space:]]+history 1$$' \
		$(BUILD_DIR)/sdb-history-run/output.log

	# Expressions are RV32 values: accepting a host-width unsigned long and
	# truncating it to uint32_t makes 2^32 silently look like zero.  Commands
	# whose usage has no arguments must likewise reject, rather than ignore,
	# trailing tokens.
	rm -rf $(BUILD_DIR)/sdb-strict-run
	mkdir -p $(BUILD_DIR)/sdb-strict-run
	printf 'p 4294967295\np 0xffffffff\np 4294967296\np 0x100000000\nc accidental\nclear accidental\nq accidental\nhistory 1\nq\n' | \
		$(NPC_BIN) $(abspath $(GOOD_BIN)) > $(BUILD_DIR)/sdb-strict-run/output.log 2>&1
	test "$$(grep -c '无符号（十进制）：4294967295' \
		$(BUILD_DIR)/sdb-strict-run/output.log)" -eq 2
	grep -Fq '十进制数字超出 uint32_t 范围: 4294967296' \
		$(BUILD_DIR)/sdb-strict-run/output.log
	grep -Fq '十六进制数字超出 uint32_t 范围: 0x100000000' \
		$(BUILD_DIR)/sdb-strict-run/output.log
	grep -Fq '用法：c' $(BUILD_DIR)/sdb-strict-run/output.log
	grep -Fq '用法：clear' $(BUILD_DIR)/sdb-strict-run/output.log
	grep -Fq '用法：q' $(BUILD_DIR)/sdb-strict-run/output.log
	grep -Fq '命令历史（显示最近 1 条，共 8 条）：' \
		$(BUILD_DIR)/sdb-strict-run/output.log
	! grep -Fq 'HIT GOOD TRAP' $(BUILD_DIR)/sdb-strict-run/output.log

	# SoC DPI MROM returns zero throughout the remainder of its fixed 256 MiB
	# window.  SDB must expose that same initialized view, while continuing to
	# reject the adjacent address instead of guessing that unrelated RAM/MMIO is
	# zero.  The earlier direct-NPC case also proves retired stores take priority
	# over the zero tail.
	$(MAKE) -C $(NPC_DIR) $(filter-out BUILD_DIR=%,$(COMMON_CONFIG)) \
		BUILD_DIR=$(SOC_BUILD_DIR) SIM_TOP=soc CONFIG_SDB=y CONFIG_TRACE=n \
		CONFIG_ITRACE=n CONFIG_ITRACE_WRITE_FILE=n CONFIG_MTRACE=n $(SOC_BIN)
	rm -rf $(BUILD_DIR)/soc-sdb-run
	mkdir -p $(BUILD_DIR)/soc-sdb-run
	printf 'x 1w 0x30000000\nx 1w 0x30000008\nx 1w 0x3ffffffc\nx 1w 0x40000000\nq\n' | \
		$(SOC_BIN) $(abspath $(GOOD_BIN)) > $(BUILD_DIR)/soc-sdb-run/output.log 2>&1
	grep -Fq '0x30000000: 0x00000513' $(BUILD_DIR)/soc-sdb-run/output.log
	grep -Fq '0x30000008: 0x00000000' $(BUILD_DIR)/soc-sdb-run/output.log
	grep -Fq '0x3ffffffc: 0x00000000' $(BUILD_DIR)/soc-sdb-run/output.log
	grep -Fq '错误：在 0x40000000 处读取内存失败' $(BUILD_DIR)/soc-sdb-run/output.log
	! grep -Fq '0x40000000: 0x00000000' $(BUILD_DIR)/soc-sdb-run/output.log

	# A precise exception is an architectural single-step boundary even though
	# the faulting instruction does not retire.  `si` must return to the command
	# loop rather than consuming handler instructions or spinning forever.
	rm -rf $(BUILD_DIR)/sdb-trap-run
	mkdir -p $(BUILD_DIR)/sdb-trap-run
	timeout 3s bash -c 'printf "si 1\nq\n" | "$$1" "$$2"' _ \
		$(NPC_BIN) $(abspath $(ILLEGAL_BIN)) \
		> $(BUILD_DIR)/sdb-trap-run/output.log 2>&1
	grep -Fq 'cause=0x00000002' $(BUILD_DIR)/sdb-trap-run/output.log
	test "$$(grep -o '(npc)' $(BUILD_DIR)/sdb-trap-run/output.log | wc -l)" -ge 2

	# The generated RTL has no $$finish path to inject from a guest.  Keep a
	# source-level guard so `si` cannot regress to stepping past gotFinish.
	grep -Fq 'completedBoundaries < count && !Verilated::gotFinish()' \
		$(NPC_DIR)/csrc/sdb/command/siCommand.cpp
	grep -Fq 'Verilator已请求结束，停止单步' \
		$(NPC_DIR)/csrc/sdb/command/siCommand.cpp
	# NVBoard GUI service belongs to the shared clock-step path so normal run,
	# SDB c and SDB si cannot diverge.  The interval must reject zero.
	test "$$(grep -c 'nvboard_update();' $(NPC_DIR)/csrc/DUT.cpp)" -eq 1
	! grep -q 'nvboard_update();' $(NPC_DIR)/csrc/main.cpp
	grep -Fq 'range 1 1000000' $(NPC_DIR)/Kconfig
	@echo '[PASS] SDB, itrace, mtrace, and direct/SoC memory observability regressions'

clean:
	rm -rf $(BUILD_DIR)
