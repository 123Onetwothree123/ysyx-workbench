SHELL := /bin/bash

NPC_DIR := $(abspath ../../..)
BUILD_DIR := $(CURDIR)/build
NO_PERF_SAVE_BUILD_DIR := $(BUILD_DIR)/no-perf-save-build
NPC_BIN := $(NO_PERF_SAVE_BUILD_DIR)/ysyx_26030103_npc
GOOD_BIN := $(BUILD_DIR)/good.bin
BAD_BIN := $(BUILD_DIR)/bad.bin
NVBOARD_GUARD_BUILD_DIR := $(BUILD_DIR)/nvboard-guard
NVBOARD_GUARD_BIN := $(NVBOARD_GUARD_BUILD_DIR)/ysyx_26030103_npc
PERF_SAVE_BUILD_DIR := $(BUILD_DIR)/perf-save-build
PERF_SAVE_BIN := $(PERF_SAVE_BUILD_DIR)/ysyx_26030103_npc
PERF_SAVE_RUN_DIR := $(BUILD_DIR)/perf-save-run

.PHONY: all test clean build-npc build-perf-npc

all: test

# Build the production executable with DiffTest deliberately disabled so the
# regression covers the formerly silent --diff acceptance path.  Clearing the
# course framework's git hook keeps a test run from committing the worktree.
build-npc:
	$(MAKE) -C $(NPC_DIR) git_commit= CONFIG_SDB=n CONFIG_DIFFTEST=n \
	  CONFIG_PERF_SAVE=n BUILD_DIR=$(abspath $(NO_PERF_SAVE_BUILD_DIR)) \
	  $(abspath $(NPC_BIN))

build-perf-npc:
	$(MAKE) -C $(NPC_DIR) git_commit= CONFIG_SDB=n CONFIG_DIFFTEST=n \
	  CONFIG_PERF_STATS=y CONFIG_PERF_SAVE=y \
	  BUILD_DIR=$(abspath $(PERF_SAVE_BUILD_DIR)) \
	  $(abspath $(PERF_SAVE_BIN))

$(BUILD_DIR):
	mkdir -p $@

$(GOOD_BIN): | $(BUILD_DIR)
	printf '\023\005\000\000\013\000\000\000' > $@

$(BAD_BIN): | $(BUILD_DIR)
	printf '\023\005\160\000\013\000\000\000' > $@

test: build-npc build-perf-npc $(GOOD_BIN) $(BAD_BIN)
	# Deliberately leave an opposite/stale program.hex behind.  The CLI image
	# must be the only source used to initialize direct-NPC RAM.
	mkdir -p $(BUILD_DIR)/build
	printf '00700513\n0000000b\n' > $(BUILD_DIR)/build/program.hex
	cd $(BUILD_DIR) && $(NPC_BIN) $(abspath $(GOOD_BIN)) > good.log 2>&1
	grep -Fq 'HIT GOOD TRAP' $(BUILD_DIR)/good.log
	@cd $(BUILD_DIR); set +e; \
	  $(NPC_BIN) $(abspath $(BAD_BIN)) > bad.log 2>&1; status=$$?; set -e; \
	  test $$status -ne 0
	grep -Fq 'HIT BAD TRAP' $(BUILD_DIR)/bad.log
	@cd $(BUILD_DIR); set +e; \
	  $(NPC_BIN) $(abspath $(GOOD_BIN)) --elf does-not-exist.elf \
	    > missing-elf.log 2>&1; status=$$?; set -e; \
	  test $$status -ne 0
	grep -Fq 'ELF 初始化失败' $(BUILD_DIR)/missing-elf.log
	! grep -Fq 'HIT GOOD TRAP' $(BUILD_DIR)/missing-elf.log
	@cd $(BUILD_DIR); set +e; \
	  $(NPC_BIN) $(abspath $(GOOD_BIN)) --diff does-not-exist.so \
	    > disabled-diff.log 2>&1; status=$$?; set -e; \
	  test $$status -ne 0
	grep -Fq 'DiffTest 初始化失败' $(BUILD_DIR)/disabled-diff.log
	grep -Fq '没开DiffTest' $(BUILD_DIR)/disabled-diff.log
	! grep -Fq 'HIT GOOD TRAP' $(BUILD_DIR)/disabled-diff.log
	# The direct top has no VGA pins.  Reject the option before constructing
	# the DUT instead of running a normal guest and reporting a bogus VGA BAD.
	@set +e; \
	  $(NPC_BIN) $(abspath $(GOOD_BIN)) --vga-check \
	    > $(BUILD_DIR)/direct-vga.log 2>&1; status=$$?; \
	  set -e; test $$status -eq 1
	grep -Fq -- '--vga-check 仅支持带 VGA 引脚的 SoC 仿真' \
	  $(BUILD_DIR)/direct-vga.log
	! grep -Fq 'HIT GOOD TRAP' $(BUILD_DIR)/direct-vga.log
	# File logging is initialized before the DUT.  A path collision must be a
	# normal diagnostic/exit 1, never an uncaught filesystem_error/abort.
	rm -rf $(BUILD_DIR)/log-init-failure
	mkdir -p $(BUILD_DIR)/log-init-failure
	printf 'occupied\n' > $(BUILD_DIR)/log-init-failure/log
	@cd $(BUILD_DIR)/log-init-failure; set +e; \
	  $(NPC_BIN) $(abspath $(GOOD_BIN)) > output.log 2>&1; status=$$?; \
	  set -e; test $$status -eq 1
	grep -Fq '日志初始化失败' $(BUILD_DIR)/log-init-failure/output.log
	! grep -Eq 'terminate called|filesystem_error|Aborted' \
	  $(BUILD_DIR)/log-init-failure/output.log
	! grep -Fq 'HIT GOOD TRAP' $(BUILD_DIR)/log-init-failure/output.log
	# --result-dir is not a silently ignored option when result saving was
	# compiled out.  Reject it before the image or DUT is initialized.
	@set +e; \
	  $(NPC_BIN) $(abspath $(GOOD_BIN)) \
	    --result-dir $(abspath $(BUILD_DIR)/disabled-result) \
	    > $(BUILD_DIR)/disabled-result.log 2>&1; status=$$?; \
	  set -e; test $$status -eq 1
	grep -Fq -- '--result-dir 需要在构建时启用 CONFIG_PERF_SAVE' \
	  $(BUILD_DIR)/disabled-result.log
	test ! -e $(BUILD_DIR)/disabled-result
	# Empty paths are invalid independently of the build-time feature switch.
	@set +e; \
	  $(PERF_SAVE_BIN) $(abspath $(GOOD_BIN)) --result-dir= \
	    > $(BUILD_DIR)/empty-result.log 2>&1; status=$$?; \
	  set -e; test $$status -eq 1
	grep -Fq -- '--result-dir 不能是空路径' $(BUILD_DIR)/empty-result.log
	! grep -Eq 'terminate called|Aborted' $(BUILD_DIR)/empty-result.log
	# With result saving enabled, both direct executable invocations below omit
	# --result-dir.  They must create separate, timestamped result directories
	# even when they finish in the same second instead of overwriting one CSV.
	rm -rf $(PERF_SAVE_RUN_DIR)
	mkdir -p $(PERF_SAVE_RUN_DIR)
	cd $(PERF_SAVE_RUN_DIR) && \
	  $(PERF_SAVE_BIN) $(abspath $(GOOD_BIN)) > default-save-1.log 2>&1
	cd $(PERF_SAVE_RUN_DIR) && \
	  $(PERF_SAVE_BIN) $(abspath $(GOOD_BIN)) > default-save-2.log 2>&1
	test "$$(find $(PERF_SAVE_RUN_DIR)/NPCSimResult -mindepth 1 -maxdepth 1 \
	  -type d | wc -l)" -eq 2
	test "$$(find $(PERF_SAVE_RUN_DIR)/NPCSimResult -mindepth 2 -maxdepth 2 \
	  -type f -name 'result_npc_*.csv' | wc -l)" -eq 2
	@find $(PERF_SAVE_RUN_DIR)/NPCSimResult -mindepth 1 -maxdepth 1 -type d \
	  -printf '%f\n' | while IFS= read -r name; do \
	    printf '%s\n' "$$name" | \
	      grep -Eq '^[0-9]{8}-[0-9]{6}(-[1-9][0-9]*)?$$' || exit 1; \
	  done
	grep -Fq 'HIT GOOD TRAP' $(PERF_SAVE_RUN_DIR)/default-save-1.log
	grep -Fq 'HIT GOOD TRAP' $(PERF_SAVE_RUN_DIR)/default-save-2.log
	# An explicit path overrides the default and must not manufacture a third
	# timestamp directory as a side effect.
	cd $(PERF_SAVE_RUN_DIR) && \
	  $(PERF_SAVE_BIN) $(abspath $(GOOD_BIN)) \
	    --result-dir explicit > explicit-save.log 2>&1
	test -f $(PERF_SAVE_RUN_DIR)/explicit/result_npc_*.csv
	test "$$(find $(PERF_SAVE_RUN_DIR)/NPCSimResult -mindepth 1 -maxdepth 1 \
	  -type d | wc -l)" -eq 2
	# A result path occupied by a regular file must fail cleanly after the run:
	# no uncaught filesystem_error, no false success message, and exit status 1.
	printf 'occupied\n' > $(PERF_SAVE_RUN_DIR)/not-a-directory
	@cd $(PERF_SAVE_RUN_DIR); set +e; \
	  $(PERF_SAVE_BIN) $(abspath $(GOOD_BIN)) \
	    --result-dir not-a-directory > save-failure.log 2>&1; status=$$?; \
	  set -e; test $$status -eq 1
	grep -Fq '性能结果保存失败' $(PERF_SAVE_RUN_DIR)/save-failure.log
	! grep -Eq 'terminate called|Aborted' $(PERF_SAVE_RUN_DIR)/save-failure.log
	! grep -Fq '单次记录:' $(PERF_SAVE_RUN_DIR)/save-failure.log
	# The implicit/default path follows the same clean failure path when the
	# NPCSimResult directory name is occupied by a regular file.
	rm -rf $(BUILD_DIR)/default-save-failure
	mkdir -p $(BUILD_DIR)/default-save-failure
	printf 'occupied\n' > $(BUILD_DIR)/default-save-failure/NPCSimResult
	@cd $(BUILD_DIR)/default-save-failure; set +e; \
	  $(PERF_SAVE_BIN) $(abspath $(GOOD_BIN)) > output.log 2>&1; status=$$?; \
	  set -e; test $$status -eq 1
	grep -Fq '性能结果保存失败' \
	  $(BUILD_DIR)/default-save-failure/output.log
	grep -Fq 'HIT GOOD TRAP' $(BUILD_DIR)/default-save-failure/output.log
	! grep -Eq 'terminate called|Aborted|单次记录:' \
	  $(BUILD_DIR)/default-save-failure/output.log
	grep -Fxq 'occupied' $(BUILD_DIR)/default-save-failure/NPCSimResult
	# A filesystem status error must become a normal CLI error.  In particular,
	# it must not escape as filesystem_error and terminate the process with 134.
	rm -rf $(BUILD_DIR)/permission-denied
	mkdir -p $(BUILD_DIR)/permission-denied/private
	cp $(GOOD_BIN) $(BUILD_DIR)/permission-denied/private/image.bin
	@set +e; \
	  chmod 000 $(BUILD_DIR)/permission-denied/private; \
	  $(NPC_BIN) $(abspath $(BUILD_DIR)/permission-denied/private/image.bin) \
	    > $(BUILD_DIR)/permission-denied.log 2>&1; status=$$?; \
	  chmod 700 $(BUILD_DIR)/permission-denied/private; \
	  set -e; test $$status -eq 1
	grep -Fq '无法查询镜像文件状态' $(BUILD_DIR)/permission-denied.log
	! grep -Eq 'filesystem_error|terminate called|Aborted' $(BUILD_DIR)/permission-denied.log
	# direct-NPC has no GPIO/VGA/UART pins for NVBoard.  Reject this invalid
	# target/configuration pair before Verilator reaches undefined symbols.
	@set +e; \
	  $(MAKE) -C $(NPC_DIR) git_commit= SIM_TOP=npc CONFIG_NVBOARD=y \
	    BUILD_DIR=$(abspath $(NVBOARD_GUARD_BUILD_DIR)) \
	    $(abspath $(NVBOARD_GUARD_BIN)) > $(BUILD_DIR)/nvboard-guard.log 2>&1; \
	  status=$$?; set -e; test $$status -ne 0
	grep -Fq 'NVBoard 只能绑定 ysyxSoCFull' $(BUILD_DIR)/nvboard-guard.log
	test ! -e $(NVBOARD_GUARD_BIN)
	grep -Fq 'CONFIG_NVBOARD_UPDATE_INTERVAL ?= 100' $(NPC_DIR)/Makefile
	@echo '[PASS] direct CLI, performance-result saving, filesystem, and NVBoard regressions'

clean:
	rm -rf $(BUILD_DIR)
