NAME = SPIFlashTest
SRCS = flash.cpp
AM_TEST_ARCH ?= riscv32i-ysyxsoc
AM_TEST_CXXFLAGS += -std=c++26 -D_GLIBCXX_ASSERTIONS

include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../../common/am-test.mk)
