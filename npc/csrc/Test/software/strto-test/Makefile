NAME = strto-test
SRCS = main.cpp

TEST_PASS_REGEX := STRTO TEST PASS
include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../../common/am-test.mk)
include $(abspath $(dir $(firstword $(MAKEFILE_LIST)))/../common/test.mk)
