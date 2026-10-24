include $(AM_HOME)/scripts/isa/riscv.mk
include $(AM_HOME)/scripts/platform/npc.mk

# RV64GC 裸机目标：rv64imafdc + Zicsr/Zifencei，LP64D 硬浮点 ABI。
#
# 与 riscv64-npc（RV64IM/lp64）刻意分开：
#   * AM_RV64GC 打开 Context 中的 f0-f31/fcsr 以及 trap.S 的浮点保存；
#   * mstatus.FS 在进入 S 模式、创建内核/用户上下文时置 Dirty；
#   * C 扩展由 -march=rv64gc 启用，NPC 的 RV64C 前端支持 16 位指令。
#
# 工具链检查：本机 riscv64-unknown-elf 配置为
#   --disable-multilib --with-arch=rv64gc --with-abi=lp64d
# 因此唯一一份 libgcc.a 就是 rv64gc/lp64d，和本目标 ABI 一致。若换到
# 只有 lp64 或 rv64im 多库的工具链，下面的 -mabi=lp64d 会直接报错，
# 而不是静默退回软浮点。
CROSS_COMPILE  := riscv64-unknown-elf-
COMMON_CFLAGS  := -fno-pic -march=rv64gc_zicsr_zifencei -mabi=lp64d \
                  -mcmodel=medany -mstrict-align -DAM_RV64GC=1
LDFLAGS        += -melf64lriscv
