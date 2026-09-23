NAME = timer-uptime-test
SRCS = main.cpp

ARCH ?= riscv32e-npc
export ARCH

include $(AM_HOME)/Makefile

override CXXFLAGS += -std=c++17 -D_GLIBCXX_ASSERTIONS
