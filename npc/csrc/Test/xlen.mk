# 测试侧 XLEN 统一入口。
#
# 所有 csrc/Test 下的 Makefile 都应 include 本文件，从 NPC Kconfig 的
# .config / include/config/auto.conf 读取 CONFIG_XLEN，而不是自己定义
# 独立的 ISA/ELF class 开关。
#
# 用法（测试目录位于 npc/csrc/Test/<name>/）：
#   include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../xlen.mk)
#
# 约定：
#   NPC_XLEN          32 或 64，来自 Kconfig
#   XLEN_IS_64        非空表示 RV64
#   XLEN_CFLAGS       仅 -DCONFIG_XLEN=...，ELF class 由核心代码统一派生
#   XLEN_AM_ARCH      abstract-machine ARCH（riscv32e-npc / riscv64-npc）
#   XLEN_GUEST_MARCH  裸机测试的 -march
#   XLEN_GUEST_MABI   裸机测试的 -mabi
#   NPC_HAS_MMU       y/n，来自 Kconfig（用于判断能否跑高虚拟地址用例）

XLEN_TEST_MAKEFILE := $(abspath $(lastword $(MAKEFILE_LIST)))
XLEN_TEST_DIR := $(patsubst %/,%,$(dir $(XLEN_TEST_MAKEFILE)))
NPC_DIR ?= $(abspath $(XLEN_TEST_DIR)/../..)

# auto.conf 是 Kconfig 生成的权威导出；不存在时回退解析 .config。
-include $(NPC_DIR)/include/config/auto.conf

ifeq ($(CONFIG_XLEN),)
NPC_CONFIG_XLEN := $(shell sed -n 's/^CONFIG_XLEN=//p' $(NPC_DIR)/.config 2>/dev/null | tail -n 1)
else
NPC_CONFIG_XLEN := $(CONFIG_XLEN)
endif

NPC_XLEN := $(or $(strip $(NPC_CONFIG_XLEN)),32)
XLEN_IS_64 := $(filter 64,$(NPC_XLEN))
NPC_HAS_MMU := $(or $(CONFIG_HAS_MMU),n)
# SoC CPU 侧位宽独立于 CPU XLEN；测试只读取，不重新解释。
NPC_SOC_XLEN := $(or $(CONFIG_SOC_XLEN),32)
NPC_SOC_XLEN_FOLLOW := $(or $(CONFIG_SOC_XLEN_FOLLOW_CPU),n)

XLEN_CFLAGS := -DCONFIG_XLEN=$(NPC_XLEN)

XLEN_AM_ARCH := $(if $(XLEN_IS_64),riscv64-npc,riscv32e-npc)
XLEN_CROSS_COMPILE ?= riscv64-unknown-elf-
XLEN_GUEST_MARCH := $(if $(XLEN_IS_64),rv64im_zicsr_zifencei,rv32im_zicsr_zifencei)
XLEN_GUEST_MABI := $(if $(XLEN_IS_64),lp64,ilp32)

# 保证配置变化时测试会重新编译；只把实际存在的文件作为依赖。
XLEN_CONFIG_STAMP := $(wildcard $(NPC_DIR)/include/config/auto.conf $(NPC_DIR)/.config)

.PHONY: xlen-show
xlen-show:
	@printf 'CONFIG_XLEN=%s\n' '$(NPC_XLEN)'
	@printf 'CONFIG_HAS_MMU=%s\n' '$(NPC_HAS_MMU)'
	@printf 'CONFIG_SOC_XLEN=%s\n' '$(NPC_SOC_XLEN)'
	@printf 'CONFIG_SOC_XLEN_FOLLOW_CPU=%s\n' '$(NPC_SOC_XLEN_FOLLOW)'
	@printf 'XLEN_AM_ARCH=%s\n' '$(XLEN_AM_ARCH)'
