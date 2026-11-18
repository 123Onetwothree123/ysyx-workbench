    .section .text
    .globl _start

/* PMU 回归：事件计数、mcountinhibit、用户别名、MINH 过滤与 Sscofpmf。 */
_start:
    /* cycles 事件 */
    csrw    mcountinhibit, x0
    li      t0, 1
    csrw    mhpmevent3, t0
    csrw    mhpmcounter3, x0
    li      t1, 1000
1:
    addi    t1, t1, -1
    bnez    t1, 1b
    csrr    t2, mhpmcounter3
    li      t3, 1000
    blt     t2, t3, fail1

    /* instructions 事件 */
    li      t0, 2
    csrw    mhpmevent3, t0
    csrw    mhpmcounter3, x0
    li      t1, 100
2:
    addi    t1, t1, -1
    bnez    t1, 2b
    csrr    t2, mhpmcounter3
    li      t3, 50
    blt     t2, t3, fail2

    /* mcountinhibit 的 HPM 位必须冻结计数 */
    li      t0, 8
    csrw    mcountinhibit, t0
    csrw    mhpmcounter3, x0
    li      t1, 100
3:
    addi    t1, t1, -1
    bnez    t1, 3b
    csrr    t2, mhpmcounter3
    bnez    t2, fail3

    /* 用户态只读别名 hpmcounter3 必须看到同一个计数值 */
    csrw    mcountinhibit, x0
    li      t1, 100
4:
    addi    t1, t1, -1
    bnez    t1, 4b
    li      t0, 8
    csrw    mcountinhibit, t0
    csrr    t2, mhpmcounter3
    li      t0, 8
    csrw    mcounteren, t0
    csrr    t3, 0xc03
    bne     t2, t3, fail4

    /* MINH：M 模式下 event1 带 MINH 时不得计数 */
    csrw    mcountinhibit, x0
    li      t0, 1
    li      t1, 1
    slli    t1, t1, 62
    or      t0, t0, t1
    csrw    mhpmevent3, t0
    csrw    mhpmcounter3, x0
    li      t1, 100
5:
    addi    t1, t1, -1
    bnez    t1, 5b
    csrr    t2, mhpmcounter3
    bnez    t2, fail5

    /* 清 MINH 后恢复计数 */
    li      t0, 1
    csrw    mhpmevent3, t0
    csrw    mhpmcounter3, x0
    li      t1, 100
6:
    addi    t1, t1, -1
    bnez    t1, 6b
    csrr    t2, mhpmcounter3
    beqz    t2, fail6

    /* 64 位回绕：OF、scountovf.bit3、mip.LCOFI */
    csrw    mhpmcounter3, x0
    li      t0, -1
    csrw    mhpmcounter3, t0
    nop
    nop
    nop
    nop
    csrr    t2, mhpmevent3
    bge     t2, x0, fail7
    csrr    t3, 0xda0
    andi    t4, t3, 8
    beqz    t4, fail8
    csrr    t4, mip
    li      t5, 0x2000
    and     t4, t4, t5
    beqz    t4, fail9

    /* 清 OF 后 scountovf 与 LCOFI pending 都应消失 */
    li      t0, 1
    csrw    mhpmevent3, t0
    csrr    t3, 0xda0
    andi    t4, t3, 8
    bnez    t4, fail10

    /* Sscofpmf 中断：cause 必须是 13 */
    la      t0, handler
    csrw    mtvec, t0
    li      t0, 0x2000
    csrw    mie, t0
    li      t0, 8
    csrw    mstatus, t0
    li      t0, 1
    csrw    mhpmevent3, t0
    li      t0, -1
    csrw    mhpmcounter3, t0
    nop
    nop
    nop
    nop
    nop
    nop
    nop
    nop
    li      a0, 7
    .word   0x0000000b

handler:
    csrr    t0, mcause
    li      t1, 0x800000000000000d
    bne     t0, t1, fail11
    li      t0, 1
    csrw    mhpmevent3, t0
    li      a0, 0
    .word   0x0000000b

fail1:
    li      a0, 1
    .word   0x0000000b
fail2:
    li      a0, 2
    .word   0x0000000b
fail3:
    li      a0, 3
    .word   0x0000000b
fail4:
    li      a0, 4
    .word   0x0000000b
fail5:
    li      a0, 5
    .word   0x0000000b
fail6:
    li      a0, 6
    .word   0x0000000b
fail7:
    li      a0, 7
    .word   0x0000000b
fail8:
    li      a0, 8
    .word   0x0000000b
fail9:
    li      a0, 9
    .word   0x0000000b
fail10:
    li      a0, 10
    .word   0x0000000b
fail11:
    li      a0, 11
    .word   0x0000000b
