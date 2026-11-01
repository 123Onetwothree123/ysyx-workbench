include $(AM_HOME)/scripts/isa/riscv.mk
include $(AM_HOME)/scripts/platform/ysyxsoc.mk

# RV64GC 裸机目标：RV64IMAFDC + Zicsr/Zifencei，LP64D ABI。
# ysyxSoC 的 AM 上下文和 trap 入口在 AM_RV64GC 下保存 f0-f31/fcsr。
CROSS_COMPILE  := riscv64-unknown-elf-
COMMON_CFLAGS  := -fno-pic -march=rv64gc_zicsr_zifencei -mabi=lp64d \
                  -mcmodel=medany -mstrict-align -DAM_RV64GC=1
LDFLAGS        += -melf64lriscv

AM_SRCS += riscv/ysyxsoc/libgcc/div.S \
           riscv/ysyxsoc/libgcc/muldi3.S \
           riscv/ysyxsoc/libgcc/multi3.c \
           riscv/ysyxsoc/libgcc/ashldi3.c \
           riscv/ysyxsoc/libgcc/unused.c
