include $(AM_HOME)/scripts/isa/riscv.mk
include $(AM_HOME)/scripts/platform/npc.mk

# RV64 裸机目标：只用 RV64IM（不启用 A/C），LP64 ABI，ELF64。
# riscv64-unknown-elf 的默认 libgcc multilib 兼容 rv64im；AM 自身不链接
# newlib/libc，因此不需要 glibc 的 rv64im 多库。
CROSS_COMPILE  := riscv64-unknown-elf-
COMMON_CFLAGS  := -fno-pic -march=rv64im_zicsr_zifencei -mabi=lp64 \
                  -mcmodel=medany -mstrict-align
LDFLAGS        += -melf64lriscv
