NAME = core-fix-guest
SRCS = main.cpp
AM_TEST_CXXFLAGS += -std=c++17 -D_GLIBCXX_ASSERTIONS

# Run against the direct NPC simulation by default.  Its internal AXI RAM is
# the component whose byte/half-word read-modify-write path this test covers.
include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../../common/am-test.mk)
