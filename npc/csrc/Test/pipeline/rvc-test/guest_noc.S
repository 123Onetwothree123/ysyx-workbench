# RISCV_C=n 负向 guest：镜像中的 .word 1 低两位为 01，是压缩编码；
# 关闭 C 时必须按整条 32 位取指槽报告 illegal instruction，绝不能把
# 它和后面的半字拼成合法 32 位指令。
#
# 本文件用 -march=rv32im（无 C）编译，保证 trap handler 自身不会生成
# 压缩指令。mainline 的 rvc-difftest-test/negative 还会检查 mtval；
# 这里做本目录自己的短回归：cause=2、mepc 精确指向 .word 槽、返回 +4。
    .section .text
    .globl _start
_start:
    la      t0, trap_handler
    csrw    mtvec, t0
    li      a0, 0
    j       compressed_site

compressed_site:
    .word   0x00000001          # 低半字是 C.NOP，高半字全零
    li      a0, 0
    j       pass

trap_handler:
    csrr    t0, mcause
    li      t1, 2
    bne     t0, t1, fail
    csrr    t0, mepc
    andi    t1, t0, 1           # 关闭 C 时 IALIGN=32，mepc 低两位为 0
    bnez    t1, fail
    la      t1, compressed_site
    bne     t0, t1, fail
    addi    t0, t0, 4
    csrw    mepc, t0
    mret

fail:
    li      a0, 1
pass:
    .word   0x0000000b
