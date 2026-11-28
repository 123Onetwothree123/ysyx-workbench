NAME = cpp-runtime-test
SRCS = main.cpp assert-reinclude.cpp
AM_TEST_CXXFLAGS += -std=c++17 -fno-exceptions -fno-rtti

TEST_PASS_REGEX := CSTDIO CMATH TEST PASS
include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../../common/am-test.mk)
include $(abspath $(dir $(firstword $(MAKEFILE_LIST)))/../common/test.mk)
