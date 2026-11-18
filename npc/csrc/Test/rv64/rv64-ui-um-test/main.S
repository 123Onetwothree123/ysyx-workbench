    # 本地 RV64UI/RV64UM 定向小子集（不是外部 riscv-arch-test）。
    # 覆盖 RV64 的 64 位 ALU、shamt[5]、*W 指令、分支/跳转、窄宽度
    # load/store 符号扩展，以及 M 扩展的 64/32 位乘除余语义。
    .section .text
    .globl _start
_start:
    li      sp, 0x80008000

.macro CHECK_EQ reg, val, code
    li      t6, \val
    bne     \reg, t6, fail\code
.endm

    # 1. RV64I 64 位算术。
    li      t0, 0x100000000
    li      t1, 1
    add     t2, t0, t1
    CHECK_EQ t2, 0x100000001, 1
    sub     t2, t0, t1
    CHECK_EQ t2, 0xffffffff, 2

    # 2. 64 位移位和 shamt[5]（RV32 会在这里出错）。
    li      t0, 1
    slli    t1, t0, 32
    CHECK_EQ t1, 0x100000000, 3
    srli    t2, t1, 32
    CHECK_EQ t2, 1, 4
    li      t0, 0x8000000000000000
    srai    t1, t0, 4
    CHECK_EQ t1, 0xf800000000000000, 5
    srli    t2, t0, 4
    CHECK_EQ t2, 0x0800000000000000, 6

    # 3. RV64 字指令低位 32 位运算 + 符号扩展。
    li      t0, 0x0000000080000001
    addiw   t1, t0, 1
    CHECK_EQ t1, 0xffffffff80000002, 7
    addw    t2, t0, t0
    CHECK_EQ t2, 2, 8
    li      t0, 0x40000000
    slliw   t1, t0, 1
    CHECK_EQ t1, 0xffffffff80000000, 9
    li      t0, -8
    sraiw   t1, t0, 1
    CHECK_EQ t1, 0xfffffffffffffffc, 10
    srliw   t2, t0, 1
    CHECK_EQ t2, 0x7ffffffc, 11

    # 4. 有符号/无符号比较。
    li      t0, -1
    slt     t1, t0, zero
    CHECK_EQ t1, 1, 12
    sltu    t1, t0, zero
    CHECK_EQ t1, 0, 13
    slti    t1, t0, 0
    CHECK_EQ t1, 1, 14
    sltiu   t1, t0, 1
    CHECK_EQ t1, 0, 15
    sltu    t1, zero, t0
    CHECK_EQ t1, 1, 16

    # 5. LUI/AUIPC。
    lui     t0, 0x12345
    CHECK_EQ t0, 0x12345000, 17
    auipc   t0, 0
    auipc   t1, 0
    sub     t2, t1, t0
    CHECK_EQ t2, 4, 18

    # 6. 条件分支的有/无符号语义。
    li      t0, -1
    blt     t0, zero, 1f
    j       fail19
1:
    bge     zero, t0, 2f
    j       fail20
2:
    bltu    t0, zero, fail21
    bgeu    zero, t0, fail22

    # 7. load 符号/零扩展。
    li      t0, 0x8899aabbccddeeff
    li      t1, 0x80004000
    sd      t0, 0(t1)
    lb      t2, 0(t1)
    CHECK_EQ t2, -1, 23
    lbu     t2, 0(t1)
    CHECK_EQ t2, 0xff, 24
    lh      t2, 0(t1)
    CHECK_EQ t2, 0xffffffffffffeeff, 25
    lhu     t2, 0(t1)
    CHECK_EQ t2, 0xeeff, 26
    lw      t2, 0(t1)
    CHECK_EQ t2, 0xffffffffccddeeff, 27
    lwu     t2, 0(t1)
    CHECK_EQ t2, 0xccddeeff, 28
    ld      t2, 0(t1)
    bne     t2, t0, fail29

    # 8. 窄宽度 store 只写对应字节。
    sd      zero, 8(t1)
    li      t0, 0x12345678
    sb      t0, 8(t1)
    sh      t0, 10(t1)
    sw      t0, 12(t1)
    ld      t2, 8(t1)
    CHECK_EQ t2, 0x1234567856780078, 30
    lw      t3, 12(t1)
    CHECK_EQ t3, 0x12345678, 31

    # 9. JAL/JALR 返回地址。
    li      t0, 0
    jal     ra, sub_jal
    CHECK_EQ t0, 0x5a, 32
    la      t1, sub_jalr
    jalr    ra, 0(t1)
after_jalr:
    CHECK_EQ t0, 0xa5, 33

    # 10. RV64M 64 位乘除余。
    li      t0, 0x100000000
    li      t1, 3
    mul     t2, t0, t1
    CHECK_EQ t2, 0x300000000, 34
    li      t0, -2
    li      t1, 3
    mulh    t2, t0, t1
    CHECK_EQ t2, -1, 35
    li      t0, -1
    li      t1, 2
    mulhu   t2, t0, t1
    CHECK_EQ t2, 1, 36
    li      t0, -2
    li      t1, 3
    mulhsu  t2, t0, t1
    CHECK_EQ t2, -1, 37
    li      t0, 0x100000000
    li      t1, 3
    mulw    t2, t0, t1
    CHECK_EQ t2, 0, 38

    li      t0, -7
    li      t1, 3
    div     t2, t0, t1
    CHECK_EQ t2, -2, 39
    rem     t2, t0, t1
    CHECK_EQ t2, -1, 40
    li      t0, -1
    li      t1, 2
    divu    t2, t0, t1
    CHECK_EQ t2, 0x7fffffffffffffff, 41
    remu    t2, t0, t1
    CHECK_EQ t2, 1, 42
    li      t0, 5
    li      t1, 0
    div     t2, t0, t1
    CHECK_EQ t2, -1, 43
    rem     t2, t0, t1
    CHECK_EQ t2, 5, 44
    li      t0, 0xffffffff80000000
    li      t1, 2
    divw    t2, t0, t1
    CHECK_EQ t2, 0xffffffffc0000000, 45
    li      t0, -7
    divw    t2, t0, t1
    CHECK_EQ t2, -3, 46
    remw    t2, t0, t1
    CHECK_EQ t2, -1, 47
    li      t0, -1
    li      t1, 2
    divuw   t2, t0, t1
    CHECK_EQ t2, 0x7fffffff, 48
    remuw   t2, t0, t1
    CHECK_EQ t2, 1, 49

    li      a0, 0
    .word   0x0000000b

sub_jal:
    li      t0, 0x5a
    ret

sub_jalr:
    li      t0, 0xa5
    ret

fail1:  li a0, 1;  .word 0x0000000b
fail2:  li a0, 2;  .word 0x0000000b
fail3:  li a0, 3;  .word 0x0000000b
fail4:  li a0, 4;  .word 0x0000000b
fail5:  li a0, 5;  .word 0x0000000b
fail6:  li a0, 6;  .word 0x0000000b
fail7:  li a0, 7;  .word 0x0000000b
fail8:  li a0, 8;  .word 0x0000000b
fail9:  li a0, 9;  .word 0x0000000b
fail10: li a0, 10; .word 0x0000000b
fail11: li a0, 11; .word 0x0000000b
fail12: li a0, 12; .word 0x0000000b
fail13: li a0, 13; .word 0x0000000b
fail14: li a0, 14; .word 0x0000000b
fail15: li a0, 15; .word 0x0000000b
fail16: li a0, 16; .word 0x0000000b
fail17: li a0, 17; .word 0x0000000b
fail18: li a0, 18; .word 0x0000000b
fail19: li a0, 19; .word 0x0000000b
fail20: li a0, 20; .word 0x0000000b
fail21: li a0, 21; .word 0x0000000b
fail22: li a0, 22; .word 0x0000000b
fail23: li a0, 23; .word 0x0000000b
fail24: li a0, 24; .word 0x0000000b
fail25: li a0, 25; .word 0x0000000b
fail26: li a0, 26; .word 0x0000000b
fail27: li a0, 27; .word 0x0000000b
fail28: li a0, 28; .word 0x0000000b
fail29: li a0, 29; .word 0x0000000b
fail30: li a0, 30; .word 0x0000000b
fail31: li a0, 31; .word 0x0000000b
fail32: li a0, 32; .word 0x0000000b
fail33: li a0, 33; .word 0x0000000b
fail34: li a0, 34; .word 0x0000000b
fail35: li a0, 35; .word 0x0000000b
fail36: li a0, 36; .word 0x0000000b
fail37: li a0, 37; .word 0x0000000b
fail38: li a0, 38; .word 0x0000000b
fail39: li a0, 39; .word 0x0000000b
fail40: li a0, 40; .word 0x0000000b
fail41: li a0, 41; .word 0x0000000b
fail42: li a0, 42; .word 0x0000000b
fail43: li a0, 43; .word 0x0000000b
fail44: li a0, 44; .word 0x0000000b
fail45: li a0, 45; .word 0x0000000b
fail46: li a0, 46; .word 0x0000000b
fail47: li a0, 47; .word 0x0000000b
fail48: li a0, 48; .word 0x0000000b
fail49: li a0, 49; .word 0x0000000b
