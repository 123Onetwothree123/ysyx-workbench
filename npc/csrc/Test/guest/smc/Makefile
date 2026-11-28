NAME = smc
SRCS = smc.cpp
AM_TEST_REQUIRE_RV32 := 1
override COMMON_CFLAGS := -fno-pic -march=rv32i_zicsr_zifencei -mcmodel=medany -mstrict-align -mabi=ilp32

include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../../common/am-test.mk)
