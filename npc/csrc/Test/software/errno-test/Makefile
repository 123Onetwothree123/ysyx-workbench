NAME = errno-test
SRCS = main.cpp
# 默认 ARCH 跟随 Kconfig 的 CONFIG_XLEN。
include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../../xlen.mk)
ARCH ?= $(XLEN_AM_ARCH)
export ARCH
include $(AM_HOME)/Makefile

TEST_PASS_REGEX := ERRNO TEST PASS
include $(abspath $(dir $(firstword $(MAKEFILE_LIST)))/../common/test.mk)
