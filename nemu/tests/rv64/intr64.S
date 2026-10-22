# RV64 中断 mcause 高 64 位编码测试：bit63=1，code=7（M 模式时钟中断）。
    .section .text
    .globl _start
_start:
    la      t0, handler
    csrw    mtvec, t0
    li      t0, 0x80             # MTIE
    csrw    mie, t0
    csrw    mip, t0              # 软件置 MTIP，下一条指令后响应
    csrs    mstatus, 8           # MIE=1
    nop
    j       fail1
handler:
    csrr    t0, mcause
    li      t1, 0x8000000000000007
    bne     t0, t1, fail2
    li      a0, 0
    .word   0x0000000b
fail1: li a0, 1; .word 0x0000000b
fail2: li a0, 2; .word 0x0000000b
