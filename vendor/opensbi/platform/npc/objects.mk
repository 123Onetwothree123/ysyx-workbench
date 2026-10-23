# SPDX-License-Identifier: BSD-2-Clause
#
# NPC 平台的默认工具链/ISA 配置。
#
# 这里只给出“无 F/D”的最小默认值；npc/scripts/build_opensbi.sh 会把
# SBI_ISA/SBI_ABI（Makefile 变量或环境覆盖）通过命令行传给 OpenSBI，
# 因此需要 F/D 时只需覆盖 SBI_ISA/SBI_ABI，不需要改本文件。

platform-objs-y += platform.o

# NPC 目前没有实现 mhartid CSR（0xf14）。OpenSBI 正常冷启动会在
# _start_warm 读取它，因此本平台改为从上一级传入的 a0 取得 boot hart
# id，并把 current_hartid() 固定为 0（单 hart）。若以后 RTL 增加
# mhartid，删除下面这行即可回到上游行为。
platform-genflags-y += -DFW_HARTID_FROM_BOOT_ARG

# NPC 的 misa 目前只报告 I/A/C（MXL 正确），没有置 S/U 位；但 RTL 的
# HAS_S/HAS_U 已经实现 S/U 特权级与 CSR。OpenSBI 用 misa_extension('S')
# 决定是否允许冷启动直接跳到 S 模式，因此本平台显式声明 S/U 可用。
# 如果以后 RTL 修正 misa.S/U，删除这一行即可。
platform-genflags-y += -DFW_ASSUME_SU_MODE

# NPC 只实现了 marchid（0xf12），没有 mvendorid/mimpid。OpenSBI 的
# vendor 扩展注册会无条件读 mvendorid，这里关闭 vendor 扩展注册并让
# BASE 扩展对缺失 CSR 返回 0，避免启动阶段非法指令。
platform-genflags-y += -DFW_NO_MVENDORID

# 64 位、单 hart、soft-float ABI。A 扩展是 OpenSBI 启动代码的硬性
# 要求（_start 用 amoswap.w 抢 boot lottery）。
PLATFORM_RISCV_XLEN = 64
PLATFORM_RISCV_ABI = lp64
PLATFORM_RISCV_ISA = rv64ima_zicsr_zifencei
PLATFORM_RISCV_CODE_MODEL = medany
