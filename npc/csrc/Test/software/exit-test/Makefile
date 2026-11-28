NAME = exit-test
SRCS = main.cpp

TEST_PASS_REGEX := ATEXIT TEST PASS
include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../../common/am-test.mk)
include $(abspath $(dir $(firstword $(MAKEFILE_LIST)))/../common/test.mk)
