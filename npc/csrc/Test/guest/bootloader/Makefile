NAME = bootloader
SRCS = bootloader.c
AM_TEST_REQUIRE_RV32 := 1
override COMMON_CFLAGS := -fno-pic -march=rv32i_zicsr_zifencei -mcmodel=medany -mstrict-align -mabi=ilp32
AM_TEST_LDFLAGS += -melf32lriscv

include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../../common/am-test.mk)
