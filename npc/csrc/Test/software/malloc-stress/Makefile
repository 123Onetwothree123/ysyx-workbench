NAME = malloc-stress
SRCS = main.cpp
# 默认 ARCH 跟随 Kconfig 的 CONFIG_XLEN。
include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../../xlen.mk)
ARCH ?= $(XLEN_AM_ARCH)
export ARCH
include $(AM_HOME)/Makefile
MALLOC_STRESS_ROUNDS ?= 200
override CXXFLAGS += -std=c++26 -D_GLIBCXX_ASSERTIONS -DMALLOC_STRESS_ROUNDS=$(MALLOC_STRESS_ROUNDS)

TEST_PASS_REGEX := MALLOC STRESS PASS
include $(abspath $(dir $(firstword $(MAKEFILE_LIST)))/../common/test.mk)
