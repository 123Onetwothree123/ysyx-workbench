# PMP granularity与WARL读回测试
    .section .text
    .globl _start
_start:
    csrw    pmpcfg0, zero
    csrw    pmpaddr0, zero

    li      t0, -1
    csrw    pmpaddr0, t0
    csrr    t1, pmpaddr0
    andi    t1, t1, 0x1f
    li      t2, 0x10
    bne     t1, t2, fail1

    li      t0, 0x18
    csrw    pmpcfg0, t0
    csrw    pmpaddr0, zero
    csrr    t1, pmpaddr0
    andi    t1, t1, 0xf
    li      t2, 7
    bne     t1, t2, fail2

    li      t0, 0x10
    csrw    pmpcfg0, t0
    csrr    t1, pmpcfg0
    andi    t1, t1, 0x18
    li      t2, 0x18
    bne     t1, t2, fail3

    li      t0, 0x20
    csrw    pmpcfg0, t0
    csrr    t1, pmpcfg0
    andi    t1, t1, 0x20
    bnez    t1, fail4

    li      t0, 0x02
    csrw    pmpcfg0, t0
    csrr    t1, pmpcfg0
    andi    t1, t1, 0x02
    bnez    t1, fail5

    csrw    pmpcfg0, zero
    li      t0, -1
    csrw    pmpaddr0, t0
    csrr    t1, pmpaddr0
    li      t2, 0xffc0000000000000
    and     t1, t1, t2
    bnez    t1, fail6

    csrw    pmpaddr0, zero
    li      a0, 0
    .word   0x0000000b
fail1:  li a0, 1; .word 0x0000000b
fail2:  li a0, 2; .word 0x0000000b
fail3:  li a0, 3; .word 0x0000000b
fail4:  li a0, 4; .word 0x0000000b
fail5:  li a0, 5; .word 0x0000000b
fail6:  li a0, 6; .word 0x0000000b
