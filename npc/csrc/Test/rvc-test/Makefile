# RV32C/RV64C 裸机回归。
#
# 目录同时保留 RV32 与 RV64 guest，运行哪一个由当前 NPC 的 CONFIG_XLEN
# 决定；DiffTest 目标需要与当前 XLEN 匹配且支持 C 扩展的 NEMU REF。
#
# 典型用法：
#   make -C npc rv32_c_defconfig
#   make -C npc verilog
#   make -C npc build/ysyx_26030103_npc
#   make -C npc/csrc/Test/rvc-test test
#
# 关闭 C 的负向用例：
#   make -C npc rv32_noc_defconfig && ... 重新构建 ...
#   make -C npc/csrc/Test/rvc-test test-noc
SHELL := /bin/bash

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
BUILD_DIR := $(TEST_DIR)/build
include $(TEST_DIR)/../xlen.mk

CROSS ?= riscv64-unknown-elf-
CC := $(CROSS)gcc
OBJCOPY := $(CROSS)objcopy
NPC_BIN ?= $(NPC_DIR)/build/ysyx_26030103_npc
REF_SO ?= $(NPC_DIR)/../nemu/build/$(if $(XLEN_IS_64),riscv64,riscv32)-nemu-interpreter-so
RUNNER := $(TEST_DIR)/run.sh

RV32_MARCH := rv32imac_zicsr_zifencei
RV32_MABI := ilp32
RV64_MARCH := rv64imac_zicsr_zifencei
RV64_MABI := lp64
# -mno-relax 同时关闭汇编器/链接器 relaxation；否则 la/j 序列缩短会移动
# 后续 .org 填充，跨 cache line/页用例就不再位于预期的 0x...ffe。
GUEST_LDFLAGS := -mno-relax -nostdlib -nostartfiles -Wl,-Ttext=0x80000000 \
	-Wl,--no-relax -Wl,--build-id=none

.PHONY: all guests test test32 test64 test-noc difftest clean xlen-show

all: test

$(BUILD_DIR):
	mkdir -p $@

define build_guest
$(BUILD_DIR)/$(1).elf: $(2) | $(BUILD_DIR)
	$$(CC) -march=$(3) -mabi=$(4) $(GUEST_LDFLAGS) -o $$@ $$<
$(BUILD_DIR)/$(1).bin: $(BUILD_DIR)/$(1).elf
	$$(OBJCOPY) -O binary $$< $$@
endef

$(eval $(call build_guest,guest-rv32,guest.S,$(RV32_MARCH),$(RV32_MABI)))
$(eval $(call build_guest,guest-rv64,guest.S,$(RV64_MARCH),$(RV64_MABI)))
$(eval $(call build_guest,guest-noc,guest_noc.S,rv32im_zicsr_zifencei,ilp32))

guests: $(BUILD_DIR)/guest-rv32.bin $(BUILD_DIR)/guest-rv64.bin \
	$(BUILD_DIR)/guest-noc.bin

# 运行与当前 .config 的 XLEN 匹配的 guest。
test: guests
	$(RUNNER) run $(NPC_BIN) $(BUILD_DIR)/guest-$(if $(XLEN_IS_64),rv64,rv32).bin \
		$(NPC_XLEN) $(BUILD_DIR)/run-$(NPC_XLEN).log

test32: $(BUILD_DIR)/guest-rv32.bin
	$(RUNNER) run $(NPC_BIN) $(BUILD_DIR)/guest-rv32.bin 32 $(BUILD_DIR)/run-32.log

test64: $(BUILD_DIR)/guest-rv64.bin
	$(RUNNER) run $(NPC_BIN) $(BUILD_DIR)/guest-rv64.bin 64 $(BUILD_DIR)/run-64.log

# 需要先用 configs/rv32_noc_defconfig 构建 RISCV_C=n 的 NPC。
test-noc: $(BUILD_DIR)/guest-noc.bin
	$(RUNNER) run $(NPC_BIN) $(BUILD_DIR)/guest-noc.bin 32 $(BUILD_DIR)/run-noc.log

# 与 NEMU RV32C/RV64C 对照。REF_SO 必须与当前 XLEN 匹配且实现 C 扩展。
difftest: guests
	$(RUNNER) difftest $(NPC_BIN) \
		$(BUILD_DIR)/guest-$(if $(XLEN_IS_64),rv64,rv32).bin \
		$(NPC_XLEN) $(BUILD_DIR)/difftest-$(NPC_XLEN).log "$(REF_SO)"

clean:
	rm -rf $(BUILD_DIR)
