# 默认 ARCH 跟随 Kconfig 的 CONFIG_XLEN。
include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../../xlen.mk)
ARCH ?= $(XLEN_AM_ARCH)
export ARCH
NAME = NVBoardLEDChaser
SRCS = ../common/NVBoardLEDChaser.cpp main.cpp
CFLAGS += -I$(CURDIR)/../common
include $(AM_HOME)/Makefile
override CXXFLAGS += -std=c++26 -D_GLIBCXX_ASSERTIONS
