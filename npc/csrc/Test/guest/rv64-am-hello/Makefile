# RV64/RV32 AM 通用裸机 hello。RV64 使用：
#   AM_HOME=<repo>/abstract-machine make ARCH=riscv64-npc
# RV32 回归使用：
#   AM_HOME=<repo>/abstract-machine make ARCH=riscv32e-npc
NAME = rv64-am-hello
SRCS = main.c
# 默认 ARCH 跟随 Kconfig 的 CONFIG_XLEN。
include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../../xlen.mk)
ARCH ?= $(XLEN_AM_ARCH)
export ARCH
include $(AM_HOME)/Makefile
