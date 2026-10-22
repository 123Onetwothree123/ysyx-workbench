include $(AM_HOME)/scripts/isa/riscv.mk
include $(AM_HOME)/scripts/platform/nemu.mk
CFLAGS  += -DISA_H=\"riscv/riscv.h\"

# RV64 NEMU 只实现 RV64IM，不实现 A/F/D/C。必须显式使用 lp64（软浮点）
# ABI，否则工具链默认的 lp64d 会让 NEMU/klib 目标文件 ABI 不一致。
CROSS_COMPILE  := riscv64-unknown-elf-
COMMON_CFLAGS  := -fno-pic -march=rv64im_zicsr_zifencei -mabi=lp64 \
                  -mcmodel=medany -mstrict-align
LDFLAGS        += -melf64lriscv

AM_SRCS += riscv/nemu/start.S \
           riscv/nemu/cte.c \
           riscv/nemu/trap.S \
           riscv/nemu/vme.c
