NAME = malloc-stress
SRCS = main.cpp
MALLOC_STRESS_ROUNDS ?= 200
AM_TEST_CXXFLAGS += -std=c++26 -D_GLIBCXX_ASSERTIONS \
	-DMALLOC_STRESS_ROUNDS=$(MALLOC_STRESS_ROUNDS)

TEST_PASS_REGEX := MALLOC STRESS PASS
include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../../common/am-test.mk)
include $(abspath $(dir $(firstword $(MAKEFILE_LIST)))/../common/test.mk)
