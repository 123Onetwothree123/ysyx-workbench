NAME = ChipLinkMemTest
SRCS = ChipLinkMemTest.cpp main.cpp
include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../../xlen.mk)
ARCH ?= $(XLEN_AM_ARCH)
export ARCH
include $(AM_HOME)/Makefile
override CXXFLAGS += -std=c++26 -D_GLIBCXX_ASSERTIONS