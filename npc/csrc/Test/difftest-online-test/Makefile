SHELL := /bin/bash

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
NPC_DIR := $(abspath $(TEST_DIR)/../../..)
ROOT_DIR := $(abspath $(NPC_DIR)/..)
NEMU_DIR := $(ROOT_DIR)/nemu
REF_SO := $(TEST_DIR)/build/riscv32-nemu-interpreter-so
REF_OBJ_DIR := $(TEST_DIR)/build/nemu-obj
LEGACY_REF_SO := $(TEST_DIR)/build/legacy-ref-without-state-size.so
DIFFTEST_LOG := $(TEST_DIR)/build/difftest-online.log
DIFFTEST_REJECT_LOG := $(TEST_DIR)/build/difftest-reject.log
NPC_BIN := $(NPC_DIR)/build/ysyx_26030103_npc
NEMU_SRCS := $(shell find $(NEMU_DIR)/src -type f \( -name '*.c' -o -name '*.h' \) -print)
HOST_CXX ?= g++

NAME = difftest-online-test
SRCS = main.cpp

ARCH ?= riscv32e-npc
export ARCH

include $(AM_HOME)/Makefile

override CXXFLAGS += -std=c++17 -D_GLIBCXX_ASSERTIONS

.PHONY: difftest

# Build and run with DiffTest explicitly enabled, independent of npc/.config.
# The output checks prevent an ordinary non-DiffTest GOOD trap from being
# mistaken for an online differential-test pass.
difftest: insert-arg $(REF_SO) $(LEGACY_REF_SO)
	@set -o pipefail; \
	  $(MAKE) -C $(NPC_DIR) CONFIG_DIFFTEST=y run \
	    file=$(IMAGE).bin elf=$(IMAGE).elf diff=$(REF_SO) 2>&1 | tee $(DIFFTEST_LOG)
	@grep -Fq 'DiffTest: ON' $(DIFFTEST_LOG)
	@grep -Fq 'DiffTest: final state PASS' $(DIFFTEST_LOG)
	@grep -Fq 'HIT GOOD TRAP' $(DIFFTEST_LOG)
	@cd $(NPC_DIR); set +e; \
	  $(NPC_BIN) $(IMAGE).bin --elf $(IMAGE).elf --diff $(LEGACY_REF_SO) \
	    > $(DIFFTEST_REJECT_LOG) 2>&1; status=$$?; set -e; \
	  cat $(DIFFTEST_REJECT_LOG); \
	  if [[ $$status -eq 0 ]]; then \
	    echo 'legacy DiffTest REF was incorrectly accepted'; exit 1; \
	  fi
	@grep -Fq 'DiffTest 初始化失败' $(DIFFTEST_REJECT_LOG)
	@! grep -Fq 'HIT GOOD TRAP' $(DIFFTEST_REJECT_LOG)

$(REF_SO): $(NEMU_SRCS) $(TEST_DIR)/Makefile
	$(MAKE) -C $(NEMU_DIR) GUEST_ISA=riscv32 ENGINE=interpreter \
		CONFIG_TARGET_SHARE=y CONFIG_TARGET_NATIVE_ELF= \
		BINARY=$(REF_SO) OBJ_DIR=$(REF_OBJ_DIR)

$(LEGACY_REF_SO): legacy_ref.cpp
	mkdir -p $(@D)
	$(HOST_CXX) -std=c++17 -O2 -Wall -Wextra -Werror -fPIC -shared -o $@ $<
