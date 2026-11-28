NAME = file-stream-test
SRCS = main.cpp
AM_TEST_CFLAGS += -I$(AM_HOME)/klib/src

TEST_PASS_REGEX := FILE STREAM TEST PASS
include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../../common/am-test.mk)
include $(abspath $(dir $(firstword $(MAKEFILE_LIST)))/../common/test.mk)
