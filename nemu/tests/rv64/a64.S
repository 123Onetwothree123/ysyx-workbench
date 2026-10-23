# RV64 A 扩展定向测试：AMO.W/.D、LR/SC、返回值与内存副作用。
# 运行方式见 nemu/tests/rv64-smoke.sh；成功时 a0=0 并命中 NEMU trap。
    .section .text
    .globl _start
_start:
    la      t0, buf

    # AMOSWAP.W：返回旧值，写入新值
    li      t1, 0x11223344
    sw      t1, 0(t0)
    li      t2, 0x55667788
    amoswap.w a0, t2, (t0)
    li      t3, 0x11223344
    bne     a0, t3, fail1
    lw      t4, 0(t0)
    li      t5, 0x55667788
    bne     t4, t5, fail1

    # AMOADD.W
    li      t2, 1
    amoadd.w a0, t2, (t0)
    li      t3, 0x55667788
    bne     a0, t3, fail2
    lw      t4, 0(t0)
    li      t5, 0x55667789
    bne     t4, t5, fail2

    # AMOXOR.W
    li      t2, 0x33333333
    amoxor.w a0, t2, (t0)
    li      t3, 0x55667789
    bne     a0, t3, fail3
    lw      t4, 0(t0)
    li      t5, 0x665544ba
    bne     t4, t5, fail3

    # AMOAND.W
    li      t1, 0xf0f0f0f0
    sw      t1, 0(t0)
    li      t2, 0x0ff00ff0
    amoand.w a0, t2, (t0)
    lw      t4, 0(t0)
    li      t5, 0x00f000f0
    bne     t4, t5, fail4

    # AMOOR.W
    li      t1, 0x00f000f0
    sw      t1, 0(t0)
    li      t2, 0x000f000f
    amoor.w a0, t2, (t0)
    lw      t4, 0(t0)
    li      t5, 0x00ff00ff
    bne     t4, t5, fail5

    # AMOMIN.W（有符号）
    li      t1, 5
    sw      t1, 0(t0)
    li      t2, 3
    amomin.w a0, t2, (t0)
    lw      t4, 0(t0)
    li      t5, 3
    bne     t4, t5, fail6

    # AMOMAX.W（有符号，含负数）
    li      t1, -1
    sw      t1, 0(t0)
    li      t2, 1
    amomax.w a0, t2, (t0)
    lw      t4, 0(t0)
    li      t5, 1
    bne     t4, t5, fail7

    # AMOMINU.W / AMOMAXU.W（无符号，0xffffffff 最大）
    li      t1, -1
    sw      t1, 0(t0)
    li      t2, 1
    amominu.w a0, t2, (t0)
    lw      t4, 0(t0)
    li      t5, 1
    bne     t4, t5, fail8

    li      t1, 1
    sw      t1, 0(t0)
    li      t2, -1
    amomaxu.w a0, t2, (t0)
    lw      t4, 0(t0)
    li      t5, -1
    bne     t4, t5, fail9

    # AMOSWAP.D / AMOADD.D
    li      t1, 0x1122334455667788
    sd      t1, 0(t0)
    li      t2, 0x0123456789abcdef
    amoswap.d a0, t2, (t0)
    li      t3, 0x1122334455667788
    bne     a0, t3, fail10
    ld      t4, 0(t0)
    li      t5, 0x0123456789abcdef
    bne     t4, t5, fail10

    li      t2, 1
    amoadd.d a0, t2, (t0)
    li      t3, 0x0123456789abcdef
    bne     a0, t3, fail11
    ld      t4, 0(t0)
    li      t5, 0x0123456789abcdf0
    bne     t4, t5, fail11

    # LR.W / SC.W：第一次成功，第二次无 reservation 必须失败
    li      t1, 0x12345678
    sw      t1, 0(t0)
    lr.w    a0, (t0)
    bne     a0, t1, fail12
    li      t2, 0x0badf00d
    sc.w    t3, t2, (t0)
    bne     t3, zero, fail13
    lw      t4, 0(t0)
    li      t5, 0x0badf00d
    bne     t4, t5, fail13
    li      t2, 0x11111111
    sc.w    t3, t2, (t0)
    li      t5, 1
    bne     t3, t5, fail14

    # LR.D / SC.D
    li      t1, 0x0102030405060708
    sd      t1, 0(t0)
    lr.d    a0, (t0)
    bne     a0, t1, fail15
    li      t2, 0x1112131415161718
    sc.d    t3, t2, (t0)
    bne     t3, zero, fail15
    ld      t4, 0(t0)
    bne     t4, t2, fail15

    li      a0, 0
    j       halt

fail1:  li a0, 1
    j       halt
fail2:  li a0, 2
    j       halt
fail3:  li a0, 3
    j       halt
fail4:  li a0, 4
    j       halt
fail5:  li a0, 5
    j       halt
fail6:  li a0, 6
    j       halt
fail7:  li a0, 7
    j       halt
fail8:  li a0, 8
    j       halt
fail9:  li a0, 9
    j       halt
fail10: li a0, 10
    j       halt
fail11: li a0, 11
    j       halt
fail12: li a0, 12
    j       halt
fail13: li a0, 13
    j       halt
fail14: li a0, 14
    j       halt
fail15: li a0, 15
    j       halt

halt:
    .word   0x0000000b          # NEMU custom-0 trap

    .section .data
    .align 3
buf:
    .dword  0
