NAME = csr-ident-test
SRCS = main.c

# 默认 ARCH/ISA 跟随 Kconfig 的 CONFIG_XLEN。
include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../xlen.mk)
ARCH ?= $(XLEN_AM_ARCH)
export ARCH

include $(AM_HOME)/Makefile

# 把当前配置的 S/U 可用性传给裸机断言，避免测试对 Kconfig 做错误假设。
# 必须放在 AM Makefile 之后：override 变量会阻止后续普通 CFLAGS 追加。
NPC_HAS_S := $(or $(CONFIG_HAS_S),n)
NPC_HAS_U := $(or $(CONFIG_HAS_U),n)
override CFLAGS += \
	-DNPC_HAS_S=$(if $(filter y,$(NPC_HAS_S)),1,0) \
	-DNPC_HAS_U=$(if $(filter y,$(NPC_HAS_U)),1,0)
