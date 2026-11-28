NAME = ChipLinkMemTest
SRCS = ChipLinkMemTest.cpp main.cpp
AM_TEST_ARCH ?= $(XLEN_SOC_ARCH)
AM_TEST_CXXFLAGS += -std=c++26 -D_GLIBCXX_ASSERTIONS

include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../../common/am-test.mk)
