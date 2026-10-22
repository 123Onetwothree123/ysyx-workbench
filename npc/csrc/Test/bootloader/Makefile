include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../xlen.mk)
ifeq ($(XLEN_IS_64),1)
$(error bootloader 是 RV32 专用裸机测试；请在 XLEN=32 配置下运行)
endif
NAME = bootloader
SRCS = bootloader.c
override COMMON_CFLAGS := -fno-pic -march=rv32i_zicsr_zifencei -mcmodel=medany -mstrict-align -mabi=ilp32
LDFLAGS += -melf32lriscv
include ${AM_HOME}/Makefile
