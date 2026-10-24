include $(AM_HOME)/scripts/isa/riscv.mk
include $(AM_HOME)/scripts/platform/nemu.mk
CFLAGS  += -DISA_H=\"riscv/riscv.h\"

# NEMU 使用 riscv64-fd_defconfig（RV64IMAFDC）或兼容的 F/D 配置时使用本目标。
# 与 riscv64-nemu（RV64IM/lp64）分开，避免在无 F/D 的 NEMU 配置上执行
# 浮点上下文保存。ABI 与工具链默认 libgcc 一致（rv64gc/lp64d）。
CROSS_COMPILE  := riscv64-unknown-elf-
COMMON_CFLAGS  := -fno-pic -march=rv64gc_zicsr_zifencei -mabi=lp64d \
                  -mcmodel=medany -mstrict-align -DAM_RV64GC=1
LDFLAGS        += -melf64lriscv

AM_SRCS += riscv/nemu/start.S \
           riscv/nemu/cte.c \
           riscv/nemu/trap.S \
           riscv/nemu/vme.c
