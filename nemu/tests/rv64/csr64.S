# RV64 64 位 CSR 读回与 mstatus.UXL/SXL/SATP 保留测试。
    .section .text
    .globl _start
_start:
    li      t0, 0x123456789abcdef0
    csrw    mscratch, t0
    csrr    t1, mscratch
    bne     t0, t1, fail1

    li      t0, 0x0000000080001234
    csrw    mepc, t0
    csrr    t1, mepc
    li      t2, 0x0000000080001234
    bne     t1, t2, fail2

    csrr    t0, mstatus
    srli    t1, t0, 32
    andi    t1, t1, 0xf
    li      t2, 0xa               # UXL=SXL=2
    bne     t1, t2, fail3

    li      t0, 0x8000000000080004 # Sv39, PPN=0x80004
    csrw    satp, t0
    csrr    t1, satp
    bne     t0, t1, fail4

    li      a0, 0
    .word   0x0000000b
fail1: li a0, 1; .word 0x0000000b
fail2: li a0, 2; .word 0x0000000b
fail3: li a0, 3; .word 0x0000000b
fail4: li a0, 4; .word 0x0000000b
