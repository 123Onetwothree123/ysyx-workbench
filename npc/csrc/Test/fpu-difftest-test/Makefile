# FPU/F/D DiffTest 回归。
#
# 正向（需要 DUT 与 REF 都实现 F/D）：
#   make rv64 BIN_RV64=/path/to/npc REF_RV64=/path/to/riscv64-nemu-interpreter-so
#   make rv32 BIN_RV32=/path/to/npc REF_RV32=/path/to/riscv32-nemu-interpreter-so
#
# 负向（RISCV_F=n / RISCV_D=n 的 DUT）：
#   make negative NEG_XLEN=64 BIN_NEG=/path/to/npc-no-f
#
# 配置错误（RISCV_D=y 无 F、FLEN=32 等必须明确失败）：
#   make config-errors NPC_DIR=/path/to/npc
#
# F/D 尚未合入时正向用例打印 [SKIP]，不会伪造 PASS；合入后 CI 使用：
#   make test STRICT=1
SHELL := /bin/bash

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
NPC_DIR ?= $(abspath $(TEST_DIR)/../../..)
include $(TEST_DIR)/../xlen.mk

NEMU_DIR ?= $(abspath $(NPC_DIR)/../nemu)
BUILD_DIR ?= $(TEST_DIR)/build
LOG_DIR ?= /tmp/opencode/fpu-difftest

CROSS_RV32 ?= riscv32-unknown-elf-
CROSS_RV64 ?= riscv64-unknown-elf-
CC32 := $(CROSS_RV32)gcc
OBJCOPY32 := $(CROSS_RV32)objcopy
CC64 := $(CROSS_RV64)gcc
OBJCOPY64 := $(CROSS_RV64)objcopy
READELF32 := $(CROSS_RV32)readelf
READELF64 := $(CROSS_RV64)readelf
OBJDUMP32 := $(CROSS_RV32)objdump
OBJDUMP64 := $(CROSS_RV64)objdump
NM32 := $(CROSS_RV32)nm
NM64 := $(CROSS_RV64)nm
HOST_CXX ?= g++

# 正向测试必须真正启用 F/D，不能靠软浮点或手工 .word 伪造。
RV32_MARCH ?= rv32imaf_zicsr
RV32_MABI ?= ilp32f
RV64_MARCH ?= rv64imafd_zicsr
RV64_MABI ?= lp64d

GUEST_LDFLAGS := -nostdlib -nostartfiles -mcmodel=medany \
	-Wl,--no-relax -Wl,--build-id=none
C_CFLAGS := -O2 -mcmodel=medany -ffreestanding -fno-builtin -fno-stack-protector \
	-fno-pic -mno-relax

PROBE_RV32 := $(BUILD_DIR)/probe-rv32f.bin
PROBE_RV64 := $(BUILD_DIR)/probe-rv64d.bin
GUEST_RV32F := $(BUILD_DIR)/guest-rv32f.bin
GUEST_RV64D := $(BUILD_DIR)/guest-rv64d.bin
TRAP_RV32F := $(BUILD_DIR)/guest-f-trap-rv32f.bin
TRAP_RV64D := $(BUILD_DIR)/guest-f-trap-rv64d.bin
GUEST_C := $(BUILD_DIR)/guest-rv64d-c.bin
C_ELF := $(GUEST_C:.bin=.elf)
GUESTS := $(PROBE_RV32) $(PROBE_RV64) $(GUEST_RV32F) $(GUEST_RV64D) \
	$(TRAP_RV32F) $(TRAP_RV64D) $(GUEST_C)

REF_PROBE := $(BUILD_DIR)/ref-probe
MISMATCH_SHIM := $(BUILD_DIR)/corrupt-ref.so
FP_MISMATCH_SHIM := $(BUILD_DIR)/corrupt-fp-ref.so
LEGACY_FP_SHIM := $(BUILD_DIR)/legacy-fp-ref.so

# DUT/REF 默认来自当前工作树；RV32/RV64 必须分别指向对应 XLEN 的资产。
BIN_RV32 ?= $(NPC_DIR)/build/ysyx_26030103_npc
BIN_RV64 ?= $(NPC_DIR)/build/ysyx_26030103_npc
REF_RV32 ?= $(NEMU_DIR)/build/riscv32-nemu-interpreter-so
REF_RV64 ?= $(NEMU_DIR)/build/riscv64-nemu-interpreter-so

NEG_XLEN ?= $(NPC_XLEN)
BIN_NEG ?= $(BIN_RV$(NEG_XLEN))
PROBE_NEG ?= $(PROBE_RV$(if $(filter 64,$(NEG_XLEN)),64,32))

MISMATCH_XLEN ?= $(NPC_XLEN)
BIN_MISMATCH ?= $(BIN_RV$(MISMATCH_XLEN))
REF_MISMATCH ?= $(REF_RV$(MISMATCH_XLEN))
GUEST_MISMATCH ?= $(PROBE_RV$(if $(filter 64,$(MISMATCH_XLEN)),64,32))

# auto = 有 mill/npc 源码时执行；0 = 跳过；1 = 强制执行。
CONFIG_CHECK ?= auto
STRICT ?= 0

.PHONY: all guests audit rv32 rv64 negative mismatch config-errors \
	fdiv-shared test test-all strict-test clean clean-logs

all: guests audit

guests: $(GUESTS)

$(BUILD_DIR):
	mkdir -p $@

# ---------------------------------------------------------------------------
# 裸机镜像
# ---------------------------------------------------------------------------
$(PROBE_RV32): guest_fpu_probe.S guest.ld | $(BUILD_DIR)
	$(CC32) -march=$(RV32_MARCH) -mabi=$(RV32_MABI) $(GUEST_LDFLAGS) \
		-T guest.ld -o $(@:.bin=.elf) $<
	$(OBJCOPY32) -O binary $(@:.bin=.elf) $@

$(PROBE_RV64): guest_fpu_probe.S guest.ld | $(BUILD_DIR)
	$(CC64) -march=$(RV64_MARCH) -mabi=$(RV64_MABI) $(GUEST_LDFLAGS) \
		-T guest.ld -o $(@:.bin=.elf) $<
	$(OBJCOPY64) -O binary $(@:.bin=.elf) $@

$(GUEST_RV32F): guest_rv32f.S guest.ld | $(BUILD_DIR)
	$(CC32) -march=$(RV32_MARCH) -mabi=$(RV32_MABI) $(GUEST_LDFLAGS) \
		-T guest.ld -o $(@:.bin=.elf) $<
	$(OBJCOPY32) -O binary $(@:.bin=.elf) $@

$(GUEST_RV64D): guest_rv64d.S guest.ld | $(BUILD_DIR)
	$(CC64) -march=$(RV64_MARCH) -mabi=$(RV64_MABI) $(GUEST_LDFLAGS) \
		-T guest.ld -o $(@:.bin=.elf) $<
	$(OBJCOPY64) -O binary $(@:.bin=.elf) $@

$(TRAP_RV32F): guest_f_trap.S guest.ld | $(BUILD_DIR)
	$(CC32) -march=$(RV32_MARCH) -mabi=$(RV32_MABI) $(GUEST_LDFLAGS) \
		-T guest.ld -o $(@:.bin=.elf) $<
	$(OBJCOPY32) -O binary $(@:.bin=.elf) $@

$(TRAP_RV64D): guest_f_trap.S guest.ld | $(BUILD_DIR)
	$(CC64) -march=$(RV64_MARCH) -mabi=$(RV64_MABI) $(GUEST_LDFLAGS) \
		-T guest.ld -o $(@:.bin=.elf) $<
	$(OBJCOPY64) -O binary $(@:.bin=.elf) $@

$(BUILD_DIR)/guest_crt0.o: guest_crt0.S | $(BUILD_DIR)
	$(CC64) -march=$(RV64_MARCH) -mabi=$(RV64_MABI) $(C_CFLAGS) -c -o $@ $<

$(BUILD_DIR)/guest_rv64d_c.o: guest_rv64d_c.c | $(BUILD_DIR)
	$(CC64) -march=$(RV64_MARCH) -mabi=$(RV64_MABI) $(C_CFLAGS) -c -o $@ $<

$(C_ELF): $(BUILD_DIR)/guest_crt0.o $(BUILD_DIR)/guest_rv64d_c.o guest_c.ld
	$(CC64) -march=$(RV64_MARCH) -mabi=$(RV64_MABI) $(GUEST_LDFLAGS) \
		-T guest_c.ld -o $@ $(filter %.o,$^)

$(GUEST_C): $(C_ELF)
	$(OBJCOPY64) -O binary $< $@

# 共享 FDIV/FSQRT 的 NEMU DiffTest 压力镜像（RV64D，整数循环 + 内联 F/D）。
FDIV_SHARED := $(BUILD_DIR)/guest-fdiv-shared.bin
$(BUILD_DIR)/guest_fdiv_shared.o: guest_fdiv_shared.c | $(BUILD_DIR)
	$(CC64) -march=$(RV64_MARCH) -mabi=$(RV64_MABI) $(C_CFLAGS) -c -o $@ $<

$(FDIV_SHARED:.bin=.elf): $(BUILD_DIR)/guest_fdiv_shared.o $(BUILD_DIR)/guest_crt0.o guest_c.ld
	$(CC64) -march=$(RV64_MARCH) -mabi=$(RV64_MABI) $(GUEST_LDFLAGS) \
		-T guest_c.ld -o $@ $(filter %.o,$^)

$(FDIV_SHARED): $(FDIV_SHARED:.bin=.elf)
	$(OBJCOPY64) -O binary $< $@

# ---------------------------------------------------------------------------
# 主机侧工具
# ---------------------------------------------------------------------------
$(REF_PROBE): ref_probe.cpp | $(BUILD_DIR)
	$(HOST_CXX) -std=c++17 -O2 -Wall -Wextra -Werror -o $@ $< -ldl

$(MISMATCH_SHIM): corrupt_ref.cpp | $(BUILD_DIR)
	$(HOST_CXX) -std=c++17 -O2 -Wall -Wextra -Werror -fPIC -shared \
		-o $@ $< -ldl

# 同一个 shim 源文件按宏编译成“注入 f2 差异”和“隐藏浮点 ABI”两个变体。
$(FP_MISMATCH_SHIM): fp_ref_shim.cpp | $(BUILD_DIR)
	$(HOST_CXX) -std=c++17 -O2 -Wall -Wextra -Werror -fPIC -shared \
		-DDIFFTEST_SHIM_CORRUPT_FP -o $@ $< -ldl

$(LEGACY_FP_SHIM): fp_ref_shim.cpp | $(BUILD_DIR)
	$(HOST_CXX) -std=c++17 -O2 -Wall -Wextra -Werror -fPIC -shared \
		-DDIFFTEST_SHIM_LEGACY_FP -o $@ $< -ldl

# ELF 审计：确保 C 源程序真的是 lp64d 硬浮点，且没有退回 libgcc 软浮点。
audit: $(C_ELF) $(GUEST_RV64D:.bin=.elf) $(GUEST_RV32F:.bin=.elf)
	@$(TEST_DIR)/audit.sh c $(READELF64) $(OBJDUMP64) $(NM64) $(C_ELF)
	@$(TEST_DIR)/audit.sh rv64d $(READELF64) $(OBJDUMP64) $(NM64) $(GUEST_RV64D:.bin=.elf)
	@$(TEST_DIR)/audit.sh rv32f $(READELF32) $(OBJDUMP32) $(NM32) $(GUEST_RV32F:.bin=.elf)

# ---------------------------------------------------------------------------
# 运行
# ---------------------------------------------------------------------------
rv32: $(PROBE_RV32) $(GUEST_RV32F) $(TRAP_RV32F) $(REF_PROBE) \
	$(FP_MISMATCH_SHIM) $(LEGACY_FP_SHIM)
	@STRICT="$(STRICT)" $(TEST_DIR)/run.sh forward 32 \
		"$(BIN_RV32)" "$(PROBE_RV32)" "$(GUEST_RV32F)" "$(TRAP_RV32F)" \
		"$(REF_RV32)" "$(REF_PROBE)" "$(LOG_DIR)"
	@STRICT="$(STRICT)" $(TEST_DIR)/run.sh fp-mismatch 32 \
		"$(BIN_RV32)" "$(PROBE_RV32)" "$(FP_MISMATCH_SHIM)" \
		"$(REF_RV32)" "$(LOG_DIR)"
	@STRICT="$(STRICT)" $(TEST_DIR)/run.sh legacy-fp 32 \
		"$(BIN_RV32)" "$(PROBE_RV32)" "$(LEGACY_FP_SHIM)" \
		"$(REF_RV32)" "$(LOG_DIR)"

rv64: $(PROBE_RV64) $(GUEST_RV64D) $(TRAP_RV64D) $(GUEST_C) $(REF_PROBE) \
	$(FP_MISMATCH_SHIM) $(LEGACY_FP_SHIM)
	@STRICT="$(STRICT)" $(TEST_DIR)/run.sh forward 64 \
		"$(BIN_RV64)" "$(PROBE_RV64)" "$(GUEST_RV64D)" "$(TRAP_RV64D)" \
		"$(REF_RV64)" "$(REF_PROBE)" "$(LOG_DIR)" "$(GUEST_C)"
	@STRICT="$(STRICT)" $(TEST_DIR)/run.sh fp-mismatch 64 \
		"$(BIN_RV64)" "$(PROBE_RV64)" "$(FP_MISMATCH_SHIM)" \
		"$(REF_RV64)" "$(LOG_DIR)"
	@STRICT="$(STRICT)" $(TEST_DIR)/run.sh legacy-fp 64 \
		"$(BIN_RV64)" "$(PROBE_RV64)" "$(LEGACY_FP_SHIM)" \
		"$(REF_RV64)" "$(LOG_DIR)"

# 大量边界/随机 F/D 除法与开方，结果与 fcsr 逐笔进入 GPR，由 NEMU
# 逐退休 DiffTest 比对；用于 FPU_FUDIAN_SHARED_DIVSQRT 的双舍入/标志回归。
fdiv-shared: $(FDIV_SHARED)
	@STRICT="$(STRICT)" $(TEST_DIR)/run.sh fdiv-shared 64 \
		"$(BIN_RV64)" "$(FDIV_SHARED)" "$(REF_RV64)" "$(LOG_DIR)"

negative: $(PROBE_NEG)
	@STRICT="$(STRICT)" $(TEST_DIR)/run.sh negative "$(NEG_XLEN)" \
		"$(BIN_NEG)" "$(PROBE_NEG)" "$(LOG_DIR)"

# 用故意注入 x5 差异的 REF shim 验证第一次分叉报告。
mismatch: $(GUEST_MISMATCH) $(MISMATCH_SHIM)
	@STRICT="$(STRICT)" $(TEST_DIR)/run.sh mismatch "$(MISMATCH_XLEN)" \
		"$(BIN_MISMATCH)" "$(GUEST_MISMATCH)" "$(MISMATCH_SHIM)" \
		"$(REF_MISMATCH)" "$(LOG_DIR)"

config-errors:
	@STRICT="$(STRICT)" CONFIG_CHECK="$(CONFIG_CHECK)" \
		$(TEST_DIR)/run.sh config "$(NPC_DIR)" "$(LOG_DIR)"

# 默认按当前 NPC 配置的 XLEN 跑正向 + 负向 + 配置错误检查。
test: guests audit
	@status=0; \
	$(MAKE) --no-print-directory rv$(NPC_XLEN) || status=1; \
	$(MAKE) --no-print-directory negative || status=1; \
	$(MAKE) --no-print-directory config-errors || status=1; \
	exit $$status

# 显式覆盖两套 XLEN 和负向/分叉资产时使用。
test-all: rv32 rv64 negative mismatch config-errors

strict-test:
	@$(MAKE) --no-print-directory test-all STRICT=1

clean:
	rm -rf $(BUILD_DIR)

clean-logs:
	rm -rf "$(LOG_DIR)"
