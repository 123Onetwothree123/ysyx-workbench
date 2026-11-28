NAME = strlen-test
SRCS = main.cpp
AM_TEST_CXXFLAGS += -std=c++26 -D_GLIBCXX_ASSERTIONS

TEST_PASS_REGEX := STRING SEARCH TEST PASS
include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../../common/am-test.mk)
include $(abspath $(dir $(firstword $(MAKEFILE_LIST)))/../common/test.mk)
