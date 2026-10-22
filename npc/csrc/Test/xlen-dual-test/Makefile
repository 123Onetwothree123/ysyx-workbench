# XLEN 双模式 csrc 回归：SDB p/x、GPR/PC、mtrace、DiffTest 拒绝路径。
#
# 本目录不定义 ISA 开关；XLEN 和 MMU/追踪能力全部来自 npc/.config 与
# include/config/auto.conf。运行前需先按目标 defconfig 构建 direct-NPC：
#   make -C npc nanos_defconfig
#   make -C npc build/ysyx_26030103_npc
#   make -C npc/csrc/Test/xlen-dual-test test
#
# RV64 高地址用例要求 CONFIG_HAS_MMU=y；SDB/mtrace/DiffTest 目标分别要求
# 对应 Kconfig 选项已启用（脚本 npc/scripts/xlen_dual_regression.sh 会统一配置）。
SHELL := /bin/bash

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
BUILD_DIR := $(TEST_DIR)/build
include $(TEST_DIR)/../xlen.mk

CROSS ?= $(XLEN_CROSS_COMPILE)
CC := $(CROSS)gcc
OBJCOPY := $(CROSS)objcopy
NPC_BIN ?= $(NPC_DIR)/build/ysyx_26030103_npc
# DiffTest 拒绝路径需要 CONFIG_DIFFTEST=y 的镜像；默认与主镜像相同。
NPC_DIFF_BIN ?= $(NPC_BIN)

GUEST_ELF := $(BUILD_DIR)/xlen-dual-$(NPC_XLEN).elf
GUEST_BIN := $(BUILD_DIR)/xlen-dual-$(NPC_XLEN).bin
GUEST_CFLAGS := -march=$(XLEN_GUEST_MARCH) -mabi=$(XLEN_GUEST_MABI) \
	-mcmodel=medany -mstrict-align -nostdlib -nostartfiles \
	-Wl,-Ttext=0x80000000 -Wl,--no-relax

.PHONY: all guest test sdb mtrace difftest-reject check-mmu check-sdb \
	check-mtrace check-difftest clean xlen-show

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
		echo "错误：需要 CONFIG_DIFFTEST=y 才能验证 DiffTest 拒绝路径"; exit 1; }

guest: $(GUEST_BIN)

$(GUEST_ELF): guest.S $(XLEN_TEST_MAKEFILE) $(XLEN_CONFIG_STAMP) | $(BUILD_DIR) check-mmu
	$(CC) $(GUEST_CFLAGS) -o $@ $<

$(GUEST_BIN): $(GUEST_ELF)
	$(OBJCOPY) -O binary $< $@

# 运行到 halt 后读取寄存器/内存：SDB 在 c 结束后仍接受命令。
sdb: guest check-sdb
	$(TEST_DIR)/run.sh sdb $(NPC_BIN) $(GUEST_BIN) $(NPC_XLEN) \
		$(BUILD_DIR)/sdb.log

# 运行到高地址 halt，检查 mtrace 的 64 位 PC/Addr/Data。
mtrace: guest check-mtrace
	$(TEST_DIR)/run.sh mtrace $(NPC_BIN) $(GUEST_BIN) $(NPC_XLEN) \
		$(BUILD_DIR)/mtrace.log

# RV64 必须在打开 REF 之前明确拒绝 DiffTest；RV32 应继续走正常加载路径。
difftest-reject: guest check-difftest
	$(TEST_DIR)/run.sh difftest $(NPC_DIFF_BIN) $(GUEST_BIN) $(NPC_XLEN) \
		$(BUILD_DIR)/difftest.log

test: sdb mtrace difftest-reject

clean:
	rm -rf $(BUILD_DIR)
