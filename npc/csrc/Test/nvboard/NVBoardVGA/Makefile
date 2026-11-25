# 默认 ARCH 跟随 Kconfig 的 CONFIG_XLEN。
include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../../xlen.mk)
ARCH ?= $(XLEN_AM_ARCH)
export ARCH
NAME = NVBoardVGA
SRCS = main.cpp
include $(AM_HOME)/Makefile
override CXXFLAGS += -std=c++26 -D_GLIBCXX_ASSERTIONS
