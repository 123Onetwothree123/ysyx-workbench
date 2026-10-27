# XLEN 双模式 csrc 回归：SDB p/x、GPR/PC、mtrace、DiffTest 正反向。
#
# 本目录不定义 ISA 开关；XLEN 和 MMU/追踪能力全部来自 npc/.config 与
# include/config/auto.conf。运行前需先按目标 defconfig 构建 direct-NPC，
# 并提供与当前 XLEN 匹配的 NEMU REF 共享库：
#   make -C npc nanos_defconfig
#   make -C npc build/ysyx_26030103_npc
#   make -C npc/csrc/Test/xlen-dual-test test \
#       REF_SO=/path/to/riscv32-nemu-interpreter-so
#
# RV64 高地址用例要求 CONFIG_HAS_MMU=y；SDB/mtrace/DiffTest 目标分别要求
# 对应 Kconfig 选项已启用。
SHELL := /bin/bash

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
BUILD_DIR := $(TEST_DIR)/build
include $(TEST_DIR)/../xlen.mk

CROSS ?= $(XLEN_CROSS_COMPILE)
CC := $(CROSS)gcc
OBJCOPY := $(CROSS)objcopy
HOST_CXX ?= g++
NPC_BIN ?= $(NPC_DIR)/build/ysyx_26030103_npc
# DiffTest REF 必须与 DUT 的 CONFIG_XLEN 一致；脚本/CI 可显式覆盖。
REF_SO ?= $(NPC_DIR)/../nemu/build/$(if $(XLEN_IS_64),riscv64,riscv32)-nemu-interpreter-so
LEGACY_REF_SO := $(BUILD_DIR)/legacy-ref32-$(NPC_XLEN).so
# 只有启用 SDB 的 DUT 接受 --batch；否则让主循环直接跑到 trap。
NPC_BATCH_FLAG := $(if $(filter y,$(CONFIG_SDB)),-b,)

GUEST_ELF := $(BUILD_DIR)/xlen-dual-$(NPC_XLEN).elf
GUEST_BIN := $(BUILD_DIR)/xlen-dual-$(NPC_XLEN).bin
GUEST_CFLAGS := -march=$(XLEN_GUEST_MARCH) -mabi=$(XLEN_GUEST_MABI) \
	-mcmodel=medany -mstrict-align -nostdlib -nostartfiles \
	-Wl,-Ttext=0x80000000 -Wl,--no-relax

.PHONY: all guest test sdb mtrace difftest difftest-reject check-mmu \
	check-sdb check-mtrace check-difftest clean xlen-show

all: test

$(BUILD_DIR):
	mkdir -p $@

check-mmu:
	@if [ "$(NPC_XLEN)" = 64 ] && [ "$(NPC_HAS_MMU)" != y ]; then \
		echo "错误：RV64 高地址用例需要 CONFIG_HAS_MMU=y（当前 .config 未启用）"; \
		exit 1; \
	fi

check-sdb:
	@test "$(CONFIG_SDB)" = y || { \
		echo "错误：需要 CONFIG_SDB=y 才能运行 SDB 用例"; exit 1; }

check-mtrace:
	@test "$(CONFIG_TRACE)" = y -a "$(CONFIG_MTRACE)" = y || { \
		echo "错误：需要 CONFIG_TRACE=y 且 CONFIG_MTRACE=y 才能运行 mtrace 用例"; exit 1; }

check-difftest:
	@test "$(CONFIG_DIFFTEST)" = y || { \
		echo "错误：需要 CONFIG_DIFFTEST=y 才能验证 DiffTest"; exit 1; }

guest: $(GUEST_BIN)

$(GUEST_ELF): guest.S $(XLEN_TEST_MAKEFILE) $(XLEN_CONFIG_STAMP) | $(BUILD_DIR) check-mmu
	$(CC) $(GUEST_CFLAGS) -o $@ $<

$(GUEST_BIN): $(GUEST_ELF)
	$(OBJCOPY) -O binary $< $@

$(LEGACY_REF_SO): legacy_ref32.cpp | $(BUILD_DIR)
	$(HOST_CXX) -std=c++17 -O2 -Wall -Wextra -Werror -fPIC -shared -o $@ $<

# 运行到 halt 后读取寄存器/内存：SDB 在 c 结束后仍接受命令。
sdb: guest check-sdb
	$(TEST_DIR)/run.sh sdb $(NPC_BIN) $(GUEST_BIN) $(NPC_XLEN) \
		$(BUILD_DIR)/sdb.log

# 运行到高地址 halt，检查 mtrace 的 64 位 PC/Addr/Data。
mtrace: guest check-mtrace
	$(TEST_DIR)/run.sh mtrace $(NPC_BIN) $(GUEST_BIN) $(NPC_XLEN) \
		$(BUILD_DIR)/mtrace.log

# RV32/RV64 都必须真正启用 REF、完成逐退休与最终状态比对。
difftest: guest check-difftest
	NPC_BATCH_FLAG="$(NPC_BATCH_FLAG)" \
		$(TEST_DIR)/run.sh difftest $(NPC_BIN) $(GUEST_BIN) $(NPC_XLEN) \
		$(BUILD_DIR)/difftest.log "$(REF_SO)"

# RV64 负向：旧 32 位 REF（132 字节状态）必须在执行前被明确拒绝。
# RV32 下 132 字节就是正确 ABI，run.sh 会跳过该用例。
difftest-reject: guest check-difftest $(LEGACY_REF_SO)
	NPC_BATCH_FLAG="$(NPC_BATCH_FLAG)" \
		$(TEST_DIR)/run.sh difftest-reject $(NPC_BIN) $(GUEST_BIN) $(NPC_XLEN) \
		$(BUILD_DIR)/difftest-reject.log "$(REF_SO)" "$(LEGACY_REF_SO)"

test: sdb mtrace difftest difftest-reject

clean:
	rm -rf $(BUILD_DIR)
