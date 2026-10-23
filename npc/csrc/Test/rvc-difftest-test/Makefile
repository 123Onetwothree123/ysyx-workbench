# RVC/RV32C/RV64C DiffTest 回归。
#
# 正向用例（需要 NPC 与 NEMU REF 都实现 C 扩展）：
#   make rv64 BIN_RV64=/path/to/npc REF_RV64=/path/to/riscv64-nemu-interpreter-so
#   make rv32 BIN_RV32=/path/to/npc REF_RV32=/path/to/riscv32-nemu-interpreter-so
#
# 负向用例（需要 RISCV_C=n 的 DUT）：
#   make negative NEG_XLEN=64 BIN_NOC=/path/to/npc-no-c
#
# 第一次分叉报告自检（任意 DUT/REF，只要 XLEN/ABI 匹配）：
#   make mismatch MISMATCH_XLEN=64 BIN_MISMATCH=... REF_MISMATCH=...
#
# RVC 尚未实现时正向用例明确打印 [SKIP]，不会伪造 PASS；合入后请在 CI
# 使用 STRICT=1 把 SKIP 升级为 FAIL：
#   make test STRICT=1
SHELL := /bin/bash

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
NPC_DIR ?= $(abspath $(TEST_DIR)/../../..)
include $(TEST_DIR)/../xlen.mk

NEMU_DIR ?= $(abspath $(NPC_DIR)/../nemu)
BUILD_DIR := $(TEST_DIR)/build
LOG_DIR ?= /tmp/opencode/rvc-difftest

CROSS_RV32 ?= riscv32-unknown-elf-
CROSS_RV64 ?= riscv64-unknown-elf-
CC32 := $(CROSS_RV32)gcc
OBJCOPY32 := $(CROSS_RV32)objcopy
CC64 := $(CROSS_RV64)gcc
OBJCOPY64 := $(CROSS_RV64)objcopy
HOST_CXX ?= g++

# 注意：C 扩展必须真正进入 -march，不能靠 .word 伪造 16 位指令。
RV32_MARCH ?= rv32imc_zicsr
RV64_MARCH ?= rv64imc_zicsr
RV32_MABI ?= ilp32
RV64_MABI ?= lp64

GUEST_CFLAGS_COMMON := -nostdlib -nostartfiles -mcmodel=medany \
	-Wl,--no-relax -Wl,--build-id=none
PROBE_LDFLAGS := $(GUEST_CFLAGS_COMMON) -T $(TEST_DIR)/guest.ld
MAIN_LDFLAGS := $(GUEST_CFLAGS_COMMON) -T $(TEST_DIR)/guest_cross.ld

PROBE_RV32 := $(BUILD_DIR)/probe-rv32.bin
PROBE_RV64 := $(BUILD_DIR)/probe-rv64.bin
GUEST_RV32 := $(BUILD_DIR)/guest-rv32c.bin
GUEST_RV64 := $(BUILD_DIR)/guest-rv64c.bin
MISMATCH_SHIM := $(BUILD_DIR)/corrupt-ref.so
GUESTS := $(PROBE_RV32) $(PROBE_RV64) $(GUEST_RV32) $(GUEST_RV64)

# DUT 默认来自当前 worktree 的构建目录；可通过变量覆盖。RV32/RV64 必须
# 分别指向对应 XLEN 的 binary，不能拿 RV32 guest 去跑 RV64 DUT。
BIN_RV32 ?= $(NPC_DIR)/build/ysyx_26030103_npc
BIN_RV64 ?= $(NPC_DIR)/build/ysyx_26030103_npc
REF_RV32 ?= $(NEMU_DIR)/build/riscv32-nemu-interpreter-so
REF_RV64 ?= $(NEMU_DIR)/build/riscv64-nemu-interpreter-so

NEG_XLEN ?= $(NPC_XLEN)
BIN_NOC ?= $(BIN_RV$(NEG_XLEN))

MISMATCH_XLEN ?= $(NPC_XLEN)
BIN_MISMATCH ?= $(BIN_RV$(MISMATCH_XLEN))
REF_MISMATCH ?= $(REF_RV$(MISMATCH_XLEN))
GUEST_MISMATCH ?= $(PROBE_RV$(MISMATCH_XLEN))

STRICT ?= 0

.PHONY: all guests rv32 rv64 negative mismatch test test-all strict-test \
	clean clean-logs

all: guests

guests: $(GUESTS)

$(BUILD_DIR):
	mkdir -p $@

$(PROBE_RV32): guest_c_probe.S guest.ld | $(BUILD_DIR)
	$(CC32) -march=$(RV32_MARCH) -mabi=$(RV32_MABI) $(PROBE_LDFLAGS) \
		-o $(@:.bin=.elf) $<
	$(OBJCOPY32) -O binary $(@:.bin=.elf) $@

$(PROBE_RV64): guest_c_probe.S guest.ld | $(BUILD_DIR)
	$(CC64) -march=$(RV64_MARCH) -mabi=$(RV64_MABI) $(PROBE_LDFLAGS) \
		-o $(@:.bin=.elf) $<
	$(OBJCOPY64) -O binary $(@:.bin=.elf) $@

$(GUEST_RV32): guest_rv32c.S guest_cross.ld | $(BUILD_DIR)
	$(CC32) -march=$(RV32_MARCH) -mabi=$(RV32_MABI) $(MAIN_LDFLAGS) \
		-o $(@:.bin=.elf) $<
	$(OBJCOPY32) -O binary $(@:.bin=.elf) $@

$(GUEST_RV64): guest_rv64c.S guest_cross.ld | $(BUILD_DIR)
	$(CC64) -march=$(RV64_MARCH) -mabi=$(RV64_MABI) $(MAIN_LDFLAGS) \
		-o $(@:.bin=.elf) $<
	$(OBJCOPY64) -O binary $(@:.bin=.elf) $@

$(MISMATCH_SHIM): corrupt_ref.cpp | $(BUILD_DIR)
	$(HOST_CXX) -std=c++17 -O2 -Wall -Wextra -Werror -fPIC -shared \
		-o $@ $< -ldl

# 正向：先跑探针判断 DUT 是否启用 RVC，再跑真实 NEMU REF --diff。
rv32: $(PROBE_RV32) $(GUEST_RV32)
	@STRICT="$(STRICT)" $(TEST_DIR)/run.sh forward 32 \
		"$(BIN_RV32)" "$(PROBE_RV32)" "$(GUEST_RV32)" "$(REF_RV32)" "$(LOG_DIR)"

rv64: $(PROBE_RV64) $(GUEST_RV64)
	@STRICT="$(STRICT)" $(TEST_DIR)/run.sh forward 64 \
		"$(BIN_RV64)" "$(PROBE_RV64)" "$(GUEST_RV64)" "$(REF_RV64)" "$(LOG_DIR)"

# 负向：DUT 的 RISCV_C=n 时，首条 C 指令必须 illegal；静默跳过 = FAIL。
negative: $(PROBE_RV$(NEG_XLEN))
	@STRICT="$(STRICT)" $(TEST_DIR)/run.sh negative "$(NEG_XLEN)" \
		"$(BIN_NOC)" "$(PROBE_RV$(NEG_XLEN))" "$(LOG_DIR)"

# 用故意注入 x5 差异的 REF shim 验证第一次分叉报告。
mismatch: $(PROBE_RV$(MISMATCH_XLEN)) $(MISMATCH_SHIM)
	@STRICT="$(STRICT)" $(TEST_DIR)/run.sh mismatch "$(MISMATCH_XLEN)" \
		"$(BIN_MISMATCH)" "$(GUEST_MISMATCH)" "$(MISMATCH_SHIM)" \
		"$(REF_MISMATCH)" "$(LOG_DIR)"

# 默认按当前 NPC 配置的 XLEN 跑正向 + 负向。RVC 合入后建议用 STRICT=1。
test: guests
	@status=0; \
	$(MAKE) --no-print-directory rv$(NPC_XLEN) || status=1; \
	$(MAKE) --no-print-directory negative || status=1; \
	exit $$status

# 显式覆盖两套 XLEN 资产时使用。
test-all: rv32 rv64 negative mismatch

strict-test:
	@$(MAKE) --no-print-directory test-all STRICT=1

clean:
	rm -rf $(BUILD_DIR)

clean-logs:
	rm -rf "$(LOG_DIR)"
