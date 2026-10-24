# RV32D + C.FP 回归：GCC rv32gc/rv64gc 真实压缩浮点二进制 + NEMU DiffTest。
#
#   make all          仅生成 rv32/rv64 裸机镜像
#   make audit        审计 ELF 中真实存在的 C.FLD/C.FSD/C.FLW/C.FSW/SP 编码
#   make ref32/ref64  用本仓库 nemu 构建 RV32IMAFDC / RV64IMAFDC REF
#   make test         用当前 NPC build/ysyx_26030103_npc 跑两套 DiffTest
#
# 默认日志写到 /tmp/opencode/rv32d-cfp，不在仓库内留下运行产物。
SHELL := /bin/bash

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
NPC_DIR ?= $(abspath $(TEST_DIR)/../../..)
NEMU_DIR ?= $(abspath $(NPC_DIR)/../nemu)
AM_HOME_DIR ?= $(abspath $(NPC_DIR)/../abstract-machine)
BUILD_DIR ?= $(TEST_DIR)/build
LOG_DIR ?= /tmp/opencode/rv32d-cfp

CROSS32 ?= riscv32-unknown-elf-
CROSS64 ?= riscv64-unknown-elf-
CC32 := $(CROSS32)gcc
OBJCOPY32 := $(CROSS32)objcopy
OBJDUMP32 := $(CROSS32)objdump
CC64 := $(CROSS64)gcc
OBJCOPY64 := $(CROSS64)objcopy
OBJDUMP64 := $(CROSS64)objdump

# 必须让 GCC 真正生成 C+F+D；不能靠 .word 手工伪造压缩指令。
RV32_MARCH ?= rv32gc
RV32_MABI ?= ilp32d
RV64_MARCH ?= rv64gc
RV64_MABI ?= lp64d

LDFLAGS := -nostdlib -nostartfiles -mcmodel=medany -fno-pic \
	-Wl,--no-relax -Wl,--build-id=none -Wl,-Ttext=0x80000000

BIN32 ?= $(NPC_DIR)/build/ysyx_26030103_npc
# RV64 C.FP 用例需要独立的 RV64 DUT；RV32 DUT 不能执行 RV64 guest。
BIN64 ?= $(NPC_DIR)/build-rv64/ysyx_26030103_npc
REF32 ?= $(NEMU_DIR)/build/riscv32-nemu-interpreter-so
REF64 ?= $(NEMU_DIR)/build/riscv64-nemu-interpreter-so

RV32_ELF := $(BUILD_DIR)/guest-rv32d-cfp.elf
RV32_BIN := $(BUILD_DIR)/guest-rv32d-cfp.bin
RV64_ELF := $(BUILD_DIR)/guest-rv64d-cfp.elf
RV64_BIN := $(BUILD_DIR)/guest-rv64d-cfp.bin

NPROC := $(shell nproc 2>/dev/null || echo 4)

.PHONY: all guests audit audit32 audit64 ref32 ref64 test clean clean-logs

all: guests audit

guests: $(RV32_BIN) $(RV64_BIN)

$(BUILD_DIR):
	mkdir -p $@

$(RV32_ELF): guest_rv32d_cfp.S | $(BUILD_DIR)
	$(CC32) -march=$(RV32_MARCH) -mabi=$(RV32_MABI) $(LDFLAGS) \
		-o $@ $<

$(RV64_ELF): guest_rv64d_cfp.S | $(BUILD_DIR)
	$(CC64) -march=$(RV64_MARCH) -mabi=$(RV64_MABI) $(LDFLAGS) \
		-o $@ $<

$(RV32_BIN): $(RV32_ELF)
	$(OBJCOPY32) -O binary $< $@

$(RV64_BIN): $(RV64_ELF)
	$(OBJCOPY64) -O binary $< $@

audit: audit32 audit64

audit32: $(RV32_ELF)
	@OBJDUMP="$(OBJDUMP32)" python3 audit_cfp.py 32 $<

audit64: $(RV64_ELF)
	@OBJDUMP="$(OBJDUMP64)" python3 audit_cfp.py 64 $<

# NEMU 的 BUILD_DIR 固定为自己的 build/；每次切换配置后重新编译。
ref32:
	$(MAKE) -C "$(NEMU_DIR)" NEMU_HOME="$(NEMU_DIR)" ARCH=riscv32-nemu \
		AM_HOME="$(AM_HOME_DIR)" riscv32-fd-ref_defconfig
	$(MAKE) -C "$(NEMU_DIR)" NEMU_HOME="$(NEMU_DIR)" ARCH=riscv32-nemu \
		AM_HOME="$(AM_HOME_DIR)" -j$(NPROC)

ref64:
	$(MAKE) -C "$(NEMU_DIR)" NEMU_HOME="$(NEMU_DIR)" ARCH=riscv64-nemu \
		AM_HOME="$(AM_HOME_DIR)" riscv64-fd-ref_defconfig
	$(MAKE) -C "$(NEMU_DIR)" NEMU_HOME="$(NEMU_DIR)" ARCH=riscv64-nemu \
		AM_HOME="$(AM_HOME_DIR)" -j$(NPROC)

test: guests audit
	@LOG_DIR="$(LOG_DIR)" BIN32="$(BIN32)" BIN64="$(BIN64)" \
		REF32="$(REF32)" REF64="$(REF64)" ./run.sh rv32

test-rv64: guests audit
	@LOG_DIR="$(LOG_DIR)" BIN64="$(BIN64)" REF64="$(REF64)" \
		./run.sh rv64

test-all: guests audit
	@LOG_DIR="$(LOG_DIR)" BIN32="$(BIN32)" BIN64="$(BIN64)" \
		REF32="$(REF32)" REF64="$(REF64)" ./run.sh all

clean:
	rm -rf "$(BUILD_DIR)"

clean-logs:
	rm -rf "$(LOG_DIR)"
