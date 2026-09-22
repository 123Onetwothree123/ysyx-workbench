NAME = core-fix-guest
SRCS = main.cpp

# Run against the direct NPC simulation by default.  Its internal AXI RAM is
# the component whose byte/half-word read-modify-write path this test covers.
ARCH ?= riscv32e-npc
export ARCH

include $(AM_HOME)/Makefile

override CXXFLAGS += -std=c++17 -D_GLIBCXX_ASSERTIONS
