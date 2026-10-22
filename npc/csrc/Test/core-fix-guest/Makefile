NAME = core-fix-guest
SRCS = main.cpp

# 默认 ARCH 跟随 Kconfig 的 CONFIG_XLEN，而非写死 RV32E。
include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../xlen.mk)

# Run against the direct NPC simulation by default.  Its internal AXI RAM is
# the component whose byte/half-word read-modify-write path this test covers.
ARCH ?= $(XLEN_AM_ARCH)
export ARCH

include $(AM_HOME)/Makefile

override CXXFLAGS += -std=c++17 -D_GLIBCXX_ASSERTIONS
