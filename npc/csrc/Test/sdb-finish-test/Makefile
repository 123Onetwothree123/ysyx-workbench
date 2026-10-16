SHELL := /bin/bash

NPC_DIR := $(abspath ../../..)
BUILD_DIR := $(CURDIR)/build
NPC_BUILD_DIR := $(BUILD_DIR)/npc-build
NPC_BIN := $(NPC_BUILD_DIR)/ysyx_26030103_npc
GOOD_BIN := $(BUILD_DIR)/good.bin
BAD_BIN := $(BUILD_DIR)/bad.bin
FINISH_BIN := $(BUILD_DIR)/finish.bin

COMMON_CONFIG := BUILD_DIR=$(NPC_BUILD_DIR) git_commit= SIM_TOP=npc \
	CONFIG_SDB=y CONFIG_NVBOARD=n CONFIG_DIFFTEST=n CONFIG_PERF_STATS=n \
	CONFIG_PERF_SAVE=n CONFIG_LOG_LEVEL=0 CONFIG_TRACE=n CONFIG_FTRACE=n \
	CONFIG_IRINGBUF=n CONFIG_WATCHPOINT=n

.PHONY: all test clean build-npc

all: test

$(BUILD_DIR):
	mkdir -p $@

# addi a0,zero,0/7 followed by the custom architectural halt instruction.
$(GOOD_BIN): | $(BUILD_DIR)
	printf '\023\005\000\000\013\000\000\000' > $@

$(BAD_BIN): | $(BUILD_DIR)
	printf '\023\005\160\000\013\000\000\000' > $@

# The bound test module recognizes only this word and executes a real $$finish.
$(FINISH_BIN): | $(BUILD_DIR)
	printf '\176\361\336\300' > $@

build-npc:
	mkdir -p $(NPC_BUILD_DIR)/verification
	cp finish_injector.v $(NPC_BUILD_DIR)/verification/finish_injector.sv
	$(MAKE) -C $(NPC_DIR) $(COMMON_CONFIG) $(NPC_BIN)

test: build-npc $(GOOD_BIN) $(BAD_BIN) $(FINISH_BIN)
	# A genuine user q before either halt mechanism remains a successful exit.
	printf 'q\n' | $(NPC_BIN) $(GOOD_BIN) > $(BUILD_DIR)/user-quit.log 2>&1
	grep -Fq 'SDB用户主动退出（程序未结束）' $(BUILD_DIR)/user-quit.log

	# $$finish is raised during image initialization, before the queued q is read.
	# It must win over q and report the absence of an architectural halt.
	@set +e; \
	  printf 'q\n' | $(NPC_BIN) $(FINISH_BIN) > $(BUILD_DIR)/finish-q.log 2>&1; \
	  status=$${PIPESTATUS[1]}; set -e; test $$status -eq 1
	grep -Fq 'Verilator已通过$$finish请求结束仿真' $(BUILD_DIR)/finish-q.log
	grep -Fq 'NPC在未触发陷阱的情况下退出' $(BUILD_DIR)/finish-q.log
	! grep -Fq 'SDB用户主动退出' $(BUILD_DIR)/finish-q.log

	# EOF after $$finish has the same non-successful result.
	@set +e; \
	  $(NPC_BIN) $(FINISH_BIN) </dev/null > $(BUILD_DIR)/finish-eof.log 2>&1; \
	  status=$$?; set -e; test $$status -eq 1
	grep -Fq 'Verilator已通过$$finish请求结束仿真' $(BUILD_DIR)/finish-eof.log
	grep -Fq 'NPC在未触发陷阱的情况下退出' $(BUILD_DIR)/finish-eof.log

	# The explicit exit-reason plumbing must not change architectural GOOD/BAD.
	printf 'c\n' | $(NPC_BIN) $(GOOD_BIN) > $(BUILD_DIR)/good.log 2>&1
	grep -Fq 'HIT GOOD TRAP' $(BUILD_DIR)/good.log
	@set +e; \
	  printf 'c\n' | $(NPC_BIN) $(BAD_BIN) > $(BUILD_DIR)/bad.log 2>&1; \
	  status=$${PIPESTATUS[1]}; set -e; test $$status -eq 1
	grep -Fq 'HIT BAD TRAP' $(BUILD_DIR)/bad.log
	@echo '[PASS] SDB explicit q/EOF/$$finish exit reasons and trap status'

clean:
	rm -rf $(BUILD_DIR)
