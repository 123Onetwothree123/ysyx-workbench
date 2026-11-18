    .section .text
    .globl _start
_start:
    li      sp, 0x80008000
    li      t0, 0x80004000
    li      t1, 0x0123456789abcdef
    sd      t1, 0(t0)
    ld      t2, 0(t0)
    bne     t1, t2, fail1
    li      t3, 0x12345678
    sw      t3, 8(t0)
    lw      t4, 8(t0)
    bne     t3, t4, fail2
    li      t3, 0x12345678
    sd      t3, 16(t0)
    ld      t4, 16(t0)
    bne     t3, t4, fail3
    jal     ra, sub
    li      t5, 0x55
    bne     a0, t5, fail4
    # cache/地址压力：写 256 个双字再求和，覆盖 ICache/DCache refill 路径。
    li      s0, 0x80010000
    li      t0, 0
    li      t1, 256
fill:
    sd      t0, 0(s0)
    addi    s0, s0, 8
    addi    t0, t0, 1
    blt     t0, t1, fill
    li      s0, 0x80010000
    li      t0, 0
    li      t2, 0
sum:
    ld      t3, 0(s0)
    add     t2, t2, t3
    addi    s0, s0, 8
    addi    t0, t0, 1
    blt     t0, t1, sum
    li      t4, 32640
    bne     t2, t4, fail5
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
sub:
    li      a0, 0x55
    ret
