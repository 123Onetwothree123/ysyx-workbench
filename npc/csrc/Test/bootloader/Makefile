NAME = bootloader
SRCS = bootloader.c
override COMMON_CFLAGS := -fno-pic -march=rv32i_zicsr_zifencei -mcmodel=medany -mstrict-align -mabi=ilp32
LDFLAGS += -melf32lriscv
include ${AM_HOME}/Makefile
