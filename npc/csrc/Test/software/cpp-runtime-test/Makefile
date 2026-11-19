NAME = cpp-runtime-test
SRCS = main.cpp assert-reinclude.cpp
# 默认 ARCH 跟随 Kconfig 的 CONFIG_XLEN。
include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../../xlen.mk)
ARCH ?= $(XLEN_AM_ARCH)
export ARCH
include $(AM_HOME)/Makefile
override CXXFLAGS += -std=c++17 -fno-exceptions -fno-rtti

TEST_PASS_REGEX := CSTDIO CMATH TEST PASS
include $(abspath $(dir $(firstword $(MAKEFILE_LIST)))/../common/test.mk)
