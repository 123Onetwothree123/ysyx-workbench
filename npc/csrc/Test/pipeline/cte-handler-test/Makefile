NAME = cte-handler-test
SRCS = main.c

# 默认 ARCH/ISA 跟随 Kconfig 的 CONFIG_XLEN。
include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../../xlen.mk)
ARCH ?= $(XLEN_AM_ARCH)
export ARCH

include $(AM_HOME)/Makefile

override CFLAGS += -std=gnu11
