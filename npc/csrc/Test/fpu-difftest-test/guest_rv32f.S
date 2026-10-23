# RV32F 正向裸机镜像。
#
# 只使用 RV32F 的指令子集：FLW/FSW、FADD/FSUB/FMUL/FDIV/FSQRT、
# FMA、FMIN/FMAX、FSGNJ、FEQ/FLT/FLE、FCVT.W/WU、FMV.X.W/FMV.W.X、
# qNaN/sNaN、舍入模式、sticky flags、mstatus.FS。
# 不包含 D 指令，也不使用软浮点替代硬浮点。
.section .data
.align 3
buf:        .zero 16
trap_cause: .word 0
trap_site:  .word 0

.section .text
.globl _start

# 单精度结果检查：fmv.x.w 会符号扩展，比较前清掉高 32 位。
.macro CHECK_S freg, expected, code
    fmv.x.w t4, \freg
    li t5, \expected
    beq t4, t5, 8f
    li a0, \code
    j halt
8:
.endm

_start:
    la t0, trap_entry
    csrw mtvec, t0

    li t0, 0x2000              # FS=Initial
    csrs mstatus, t0
    csrr t0, mstatus
    li t1, 0x6000
    and t0, t0, t1
    li t1, 0x2000
    bne t0, t1, fail1

    csrw fcsr, zero

    # FLW / FSW
    la s0, buf
    li t0, 0x3fc00000          # 1.5f
    sw t0, 0(s0)
    flw ft0, 0(s0)
    CHECK_S ft0, 0x3fc00000, 2

    li t0, 0x40100000          # 2.25f
    fmv.w.x ft1, t0
    fsw ft1, 4(s0)
    lw t1, 4(s0)
    li t2, 0x40100000
    bne t1, t2, fail3

    # 算术
    csrw fcsr, zero
    li t0, 0x3fc00000          # 1.5f
    fmv.w.x ft0, t0
    li t0, 0x40100000          # 2.25f
    fmv.w.x ft1, t0
    fadd.s ft2, ft0, ft1
    CHECK_S ft2, 0x40700000, 4       # 3.75f
    csrr t0, fcsr
    andi t0, t0, 0x1f
    bnez t0, fail4

    li t0, 0x40400000          # 3.0f
    fmv.w.x ft3, t0
    fsub.s ft2, ft0, ft3
    CHECK_S ft2, 0xbfc00000, 5       # -1.5f

    li t0, 0x40400000
    fmv.w.x ft0, t0
    li t0, 0x40200000          # 2.5f
    fmv.w.x ft1, t0
    fmul.s ft2, ft0, ft1
    CHECK_S ft2, 0x40f00000, 6       # 7.5f

    csrw fcsr, zero
    li t0, 0x3f800000
    fmv.w.x ft0, t0
    li t0, 0x40400000
    fmv.w.x ft1, t0
    fdiv.s ft2, ft0, ft1
    CHECK_S ft2, 0x3eaaaaab, 7
    csrr t0, fcsr
    andi t0, t0, 0x1f
    li t1, 1
    bne t0, t1, fail7

    csrw fcsr, zero
    li t0, 0x40000000          # 2.0f
    fmv.w.x ft0, t0
    fsqrt.s ft2, ft0
    CHECK_S ft2, 0x3fb504f3, 8
    csrr t0, fcsr
    andi t0, t0, 0x1f
    li t1, 1
    bne t0, t1, fail8

    # FMA
    li t0, 0x40000000          # 2.0f
    fmv.w.x ft0, t0
    li t0, 0x40400000          # 3.0f
    fmv.w.x ft1, t0
    li t0, 0x3f800000          # 1.0f
    fmv.w.x ft3, t0
    fmadd.s ft2, ft0, ft1, ft3
    CHECK_S ft2, 0x40e00000, 9       # 7
    fmsub.s ft2, ft0, ft1, ft3
    CHECK_S ft2, 0x40a00000, 10      # 5
    fnmsub.s ft2, ft0, ft1, ft3
    CHECK_S ft2, 0xc0a00000, 11      # -5
    fnmadd.s ft2, ft0, ft1, ft3
    CHECK_S ft2, 0xc0e00000, 12      # -7

    # MIN/MAX
    li t0, 0x80000000
    fmv.w.x ft0, t0
    li t0, 0x00000000
    fmv.w.x ft1, t0
    fmin.s ft2, ft0, ft1
    CHECK_S ft2, 0x80000000, 13
    fmax.s ft2, ft0, ft1
    CHECK_S ft2, 0x00000000, 13

    li t0, 0x7fc00000          # qNaN
    fmv.w.x ft0, t0
    li t0, 0x40400000
    fmv.w.x ft1, t0
    csrw fcsr, zero
    fmin.s ft2, ft0, ft1
    CHECK_S ft2, 0x40400000, 14
    fmax.s ft2, ft1, ft0
    CHECK_S ft2, 0x40400000, 14
    fmin.s ft2, ft0, ft0
    CHECK_S ft2, 0x7fc00000, 14
    csrr t0, fcsr
    andi t0, t0, 0x1f
    bnez t0, fail14

    # FSGNJ
    li t0, 0x3fc00000          # 1.5f
    fmv.w.x ft0, t0
    li t0, 0xc0000000          # -2.0f
    fmv.w.x ft1, t0
    fsgnj.s ft2, ft0, ft1
    CHECK_S ft2, 0xbfc00000, 15
    fsgnjn.s ft2, ft0, ft1
    CHECK_S ft2, 0x3fc00000, 16
    fsgnjx.s ft2, ft0, ft1
    CHECK_S ft2, 0xbfc00000, 17

    # 比较
    li t0, 0x3f800000
    fmv.w.x ft0, t0
    li t0, 0x40000000
    fmv.w.x ft1, t0
    feq.s a0, ft0, ft1
    bnez a0, fail18
    flt.s a0, ft0, ft1
    beqz a0, fail18
    fle.s a0, ft0, ft0
    beqz a0, fail18

    li t0, 0x7fc00000
    fmv.w.x ft0, t0
    li t0, 0x3f800000
    fmv.w.x ft1, t0
    feq.s a0, ft0, ft1
    bnez a0, fail19
    flt.s a0, ft0, ft1
    bnez a0, fail19
    fle.s a0, ft1, ft0
    bnez a0, fail19

    li t0, 0x7f800001          # sNaN
    fmv.w.x ft0, t0
    csrw fcsr, zero
    flt.s a0, ft0, ft1
    bnez a0, fail20
    csrr t0, fcsr
    andi t0, t0, 0x1f
    li t1, 0x10
    bne t0, t1, fail20

    # FCVT
    li t0, 0x40766666          # 3.7f
    fmv.w.x ft0, t0
    fcvt.w.s a0, ft0, rtz
    li t1, 3
    bne a0, t1, fail21
    fcvt.w.s a0, ft0, rne
    li t1, 4
    bne a0, t1, fail21

    li t0, 0x40200000          # 2.5f
    fmv.w.x ft0, t0
    fcvt.w.s a0, ft0, rne
    li t1, 2                   # ties-to-even
    bne a0, t1, fail22
    fcvt.w.s a0, ft0, rmm
    li t1, 3                   # ties-away
    bne a0, t1, fail22

    li t0, 0xbf800000          # -1.0f
    fmv.w.x ft0, t0
    csrw fcsr, zero
    fcvt.wu.s a0, ft0, rtz
    bnez a0, fail23
    csrr t0, fcsr
    andi t0, t0, 0x1f
    li t1, 0x10
    bne t0, t1, fail23

    li a0, -7
    fcvt.s.w ft0, a0
    CHECK_S ft0, 0xc0e00000, 24

    li t0, 0x80000000
    fmv.w.x ft0, t0
    fmv.x.w a0, ft0
    li t1, 0xffffffff80000000
    bne a0, t1, fail25

    li t0, 0xdeadbeef
    fmv.w.x ft0, t0
    fmv.x.w a0, ft0
    li t1, 0xffffffffdeadbeef
    bne a0, t1, fail25

    # NaN 规范化
    li t0, 0x7fc00000
    fmv.w.x ft0, t0
    li t0, 0x3f800000
    fmv.w.x ft1, t0
    csrw fcsr, zero
    fadd.s ft2, ft0, ft1
    CHECK_S ft2, 0x7fc00000, 26
    csrr t0, fcsr
    andi t0, t0, 0x1f
    bnez t0, fail26

    li t0, 0x7f800001          # sNaN
    fmv.w.x ft0, t0
    csrw fcsr, zero
    fadd.s ft2, ft0, ft1
    CHECK_S ft2, 0x7fc00000, 27
    csrr t0, fcsr
    andi t0, t0, 0x1f
    li t1, 0x10
    bne t0, t1, fail27

    # 舍入：1.0f + 2^-24 是中点
    li t0, 0x3f800000
    fmv.w.x ft0, t0
    li t0, 0x33800000
    fmv.w.x ft1, t0
    fadd.s ft2, ft0, ft1, rne
    CHECK_S ft2, 0x3f800000, 28
    fadd.s ft2, ft0, ft1, rtz
    CHECK_S ft2, 0x3f800000, 28
    fadd.s ft2, ft0, ft1, rdn
    CHECK_S ft2, 0x3f800000, 28
    fadd.s ft2, ft0, ft1, rup
    CHECK_S ft2, 0x3f800001, 29
    fadd.s ft2, ft0, ft1, rmm
    CHECK_S ft2, 0x3f800001, 29

    li t0, 3
    fsrm t0
    frrm a0
    li t1, 3
    bne a0, t1, fail30
    fadd.s ft2, ft0, ft1       # dyn -> RUP
    CHECK_S ft2, 0x3f800001, 30

    # sticky flags / fscsr
    csrw fcsr, zero
    li t0, 0x3f800000
    fmv.w.x ft0, t0
    li t0, 0x40400000
    fmv.w.x ft1, t0
    fdiv.s ft2, ft0, ft1
    frflags a0
    li t1, 1
    bne a0, t1, fail31
    fadd.s ft2, ft0, ft0
    frflags a0
    bne a0, t1, fail31
    li t0, 0x10
    fsflags t0
    frflags a0
    li t1, 0x11
    bne a0, t1, fail31

    li t0, 0x24
    fscsr t0
    frrm a0
    li t1, 1
    bne a0, t1, fail32
    frflags a0
    li t1, 4
    bne a0, t1, fail32

    # FLEN=32 / FS Dirty
    li t0, 0xdeadbeef
    fmv.w.x ft0, t0
    fmv.x.w a0, ft0
    li t1, 0xffffffffdeadbeef
    bne a0, t1, fail33
    csrr t0, mstatus
    li t1, 0x6000
    and t0, t0, t1
    li t1, 0x6000
    bne t0, t1, fail34

    li a0, 0
    j halt

fail1:  li a0, 1;  j halt
fail2:  li a0, 2;  j halt
fail3:  li a0, 3;  j halt
fail4:  li a0, 4;  j halt
fail5:  li a0, 5;  j halt
fail6:  li a0, 6;  j halt
fail7:  li a0, 7;  j halt
fail8:  li a0, 8;  j halt
fail9:  li a0, 9;  j halt
fail10: li a0, 10; j halt
fail11: li a0, 11; j halt
fail12: li a0, 12; j halt
fail13: li a0, 13; j halt
fail14: li a0, 14; j halt
fail15: li a0, 15; j halt
fail16: li a0, 16; j halt
fail17: li a0, 17; j halt
fail18: li a0, 18; j halt
fail19: li a0, 19; j halt
fail20: li a0, 20; j halt
fail21: li a0, 21; j halt
fail22: li a0, 22; j halt
fail23: li a0, 23; j halt
fail24: li a0, 24; j halt
fail25: li a0, 25; j halt
fail26: li a0, 26; j halt
fail27: li a0, 27; j halt
fail28: li a0, 28; j halt
fail29: li a0, 29; j halt
fail30: li a0, 30; j halt
fail31: li a0, 31; j halt
fail32: li a0, 32; j halt
fail33: li a0, 33; j halt
fail34: li a0, 34; j halt

halt:
    .word 0x0000000b

.align 2
trap_entry:
    csrr t0, mcause
    la t1, trap_cause
    sw t0, 0(t1)
    csrr t0, mepc
    la t1, trap_site
    sw t0, 0(t1)
    li a0, 70
    j halt
