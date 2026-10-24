include $(AM_HOME)/scripts/isa/riscv.mk
include $(AM_HOME)/scripts/platform/npc.mk

# 32 位非 E 的 direct-NPC 目标：RV32IMA + Zicsr/Zifencei，ILP32 ABI。
# 与 riscv32e-npc 的区别是保留 32 个通用寄存器、使用 a7 传系统调用号，
# 因此可以直接运行 Navy-apps 的 rv32g/ilp32 用户程序；同时不使用 C/F/D
# 指令，匹配 NPC 默认的 RV32IMA 配置。
CROSS_COMPILE  := riscv64-linux-gnu-
COMMON_CFLAGS  += -march=rv32ima_zicsr_zifencei -mabi=ilp32
LDFLAGS        += -melf32lriscv
