SHELL := /bin/bash

NPC_DIR := $(abspath ../../..)
NPC_BIN := $(NPC_DIR)/build/ysyx_26030103_npc
BUILD_DIR := $(CURDIR)/build
GOOD_BIN := $(BUILD_DIR)/good.bin
BAD_BIN := $(BUILD_DIR)/bad.bin

.PHONY: all test clean build-npc

all: test

# Build the production executable with DiffTest deliberately disabled so the
# regression covers the formerly silent --diff acceptance path.  Clearing the
# course framework's git hook keeps a test run from committing the worktree.
build-npc:
	$(MAKE) -C $(NPC_DIR) git_commit= CONFIG_SDB=n CONFIG_DIFFTEST=n \
	  CONFIG_PERF_SAVE=n build/ysyx_26030103_npc

$(BUILD_DIR):
	mkdir -p $@

$(GOOD_BIN): | $(BUILD_DIR)
	printf '\023\005\000\000\013\000\000\000' > $@

$(BAD_BIN): | $(BUILD_DIR)
	printf '\023\005\160\000\013\000\000\000' > $@

test: build-npc $(GOOD_BIN) $(BAD_BIN)
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
	@echo '[PASS] direct CLI image, missing ELF, and disabled DiffTest regressions'

clean:
	rm -rf $(BUILD_DIR)
