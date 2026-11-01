include $(AM_HOME)/scripts/isa/riscv.mk
include $(AM_HOME)/scripts/platform/ysyxsoc.mk

# RV64IM 裸机目标：与 riscv64-npc/nemu 的软件 ABI 保持一致（lp64，无 F/D）。
CROSS_COMPILE  := riscv64-unknown-elf-
COMMON_CFLAGS  := -fno-pic -march=rv64im_zicsr_zifencei -mabi=lp64 \
                  -mcmodel=medany -mstrict-align
LDFLAGS        += -melf64lriscv

AM_SRCS += riscv/ysyxsoc/libgcc/div.S \
           riscv/ysyxsoc/libgcc/muldi3.S \
           riscv/ysyxsoc/libgcc/multi3.c \
           riscv/ysyxsoc/libgcc/ashldi3.c \
           riscv/ysyxsoc/libgcc/unused.c
