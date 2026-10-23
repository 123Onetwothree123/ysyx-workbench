# RV32IMAF 定向测试：仅 F 扩展（无 D），含 FMA、NaN、舍入、flags、
# FLW/FSW、FMV 和 D 编码非法检查。
    .section .text
    .globl _start

.macro CHECK reg, val, code
    li      t6, \val
    beq     \reg, t6, 9f
    li      a0, \code
    .word   0x0000000b
9:
.endm

.macro CHECK_R reg, expect, code
    beq     \reg, \expect, 9f
    li      a0, \code
    .word   0x0000000b
9:
.endm

_start:
    li      sp, 0x87fffff0
    li      t0, 0x2000
    csrs    mstatus, t0

    # misa: F=1, D=0
    csrr    t0, misa
    srli    t1, t0, 5
    andi    t1, t1, 1
    CHECK   t1, 1, 1
    srli    t1, t0, 3
    andi    t1, t1, 1
    CHECK   t1, 0, 2

    # 1.5f + 2.5f = 4.0f
    li      t0, 0x3fc00000
    fmv.w.x ft0, t0
    li      t0, 0x40200000
    fmv.w.x ft1, t0
    fadd.s  ft2, ft0, ft1
    fmv.x.w a0, ft2
    CHECK   a0, 0x40800000, 3
    fmul.s  ft2, ft0, ft1
    fmv.x.w a0, ft2
    CHECK   a0, 0x40700000, 4
    li      t0, 0x41100000
    fmv.w.x ft3, t0
    fsqrt.s ft2, ft3
    fmv.x.w a0, ft2
    CHECK   a0, 0x40400000, 5
    csrw    fflags, zero
    fmv.w.x ft4, zero
    fdiv.s  ft2, ft0, ft4
    fmv.x.w a0, ft2
    CHECK   a0, 0x7f800000, 6
    csrr    t0, fflags
    CHECK   t0, 0x08, 7

    # FMA.S：1.5*2.5+0.25 = 4.0
    li      t0, 0x3e800000
    fmv.w.x ft3, t0
    fmadd.s ft2, ft0, ft1, ft3
    fmv.x.w a0, ft2
    CHECK   a0, 0x40800000, 8
    fnmsub.s ft2, ft0, ft1, ft3
    fmv.x.w a0, ft2
    CHECK   a0, 0xc0600000, 9

    # sNaN -> canonical + NV
    csrw    fflags, zero
    li      t0, 0x7f800001
    fmv.w.x ft0, t0
    li      t0, 0x3f800000
    fmv.w.x ft1, t0
    fadd.s  ft2, ft0, ft1
    fmv.x.w a0, ft2
    CHECK   a0, 0x7fc00000, 10
    csrr    t0, fflags
    CHECK   t0, 0x10, 11

    # 舍入：1.0f + 2^-24
    li      t0, 0x3f800000
    fmv.w.x ft0, t0
    li      t0, 0x33800000
    fmv.w.x ft1, t0
    csrw    frm, zero
    fadd.s  ft2, ft0, ft1
    fmv.x.w a0, ft2
    CHECK   a0, 0x3f800000, 12
    li      t0, 3
    csrw    frm, t0
    fadd.s  ft2, ft0, ft1
    fmv.x.w a0, ft2
    CHECK   a0, 0x3f800001, 13
    csrw    frm, zero

    # 比较、FCLASS、FSGNJ
    li      t0, 0x7fc00000
    fmv.w.x ft0, t0
    li      t0, 0x3f800000
    fmv.w.x ft1, t0
    csrw    fflags, zero
    feq.s   a0, ft0, ft1
    CHECK   a0, 0, 14
    csrr    t0, fflags
    CHECK   t0, 0, 15
    csrw    fflags, zero
    flt.s   a0, ft0, ft1
    CHECK   a0, 0, 16
    csrr    t0, fflags
    CHECK   t0, 0x10, 17
    li      t0, 0x7f800000
    fmv.w.x ft0, t0
    fclass.s a0, ft0
    CHECK   a0, 0x80, 18
    fsgnjn.s ft2, ft0, ft0
    fmv.x.w a0, ft2
    CHECK   a0, 0xff800000, 19

    # 转换
    li      t0, 0x40600000
    fmv.w.x ft0, t0
    fcvt.w.s a0, ft0
    CHECK   a0, 4, 20
    fcvt.w.s a0, ft0, rtz
    CHECK   a0, 3, 21
    li      t0, -7
    fcvt.s.w ft0, t0
    fmv.x.w a0, ft0
    CHECK   a0, 0xc0e00000, 22
    li      t0, -1
    fcvt.s.wu ft0, t0
    fmv.x.w a0, ft0
    CHECK   a0, 0x4f800000, 23

    # FLW/FSW（F-only 下不能用 FSD 观察高 32 位）
    la      t0, data
    flw     ft0, 0(t0)
    fmv.x.w a0, ft0
    CHECK   a0, 0x40490fdb, 24
    fsw     ft0, 4(t0)
    lw      a0, 4(t0)
    CHECK   a0, 0x40490fdb, 25
    la      t0, data
    lw      t1, 8(t0)
    sw      t1, 0(sp)
    sw      zero, 4(sp)
    la      t0, trap_handler
    csrw    mtvec, t0
d_inst:
    # fld ft0, 0(sp)：汇编器在 rv32imaf 下拒绝发 D 指令，这里写机器码。
    .word   0x00013007
    j       fail_d
d_resume:

    # mstatus.FS=Off -> F 指令非法
    la      t0, trap_handler
    csrw    mtvec, t0
    li      t0, 0x6000
    csrc    mstatus, t0
    la      t1, fs_off_inst
fs_off_inst:
    fadd.s  ft0, ft0, ft0
    j       fail_fs
fs_resume:
    fadd.s  ft2, ft1, ft1
    csrr    t0, mstatus
    srli    t0, t0, 13
    andi    t0, t0, 3
    CHECK   t0, 3, 27

    li      a0, 0
    .word   0x0000000b

# 统一的非法指令/FS-off trap handler：只处理 4 字节指令。
    .align  2
trap_handler:
    csrr    t0, mcause
    CHECK   t0, 2, 28
    csrr    t0, mepc
    la      t1, fs_off_inst
    beq     t0, t1, 1f
    la      t1, d_inst
    CHECK_R t0, t1, 29
    li      t0, 0x2000
    csrs    mstatus, t0
    la      t0, d_resume
    csrw    mepc, t0
    mret
1:
    li      t0, 0x2000
    csrs    mstatus, t0
    la      t0, fs_resume
    csrw    mepc, t0
    mret

fail_d:
    li      a0, 30
    .word   0x0000000b
fail_fs:
    li      a0, 31
    .word   0x0000000b

    .section .data
    .align  3
data:
    .word   0x40490fdb
    .word   0xdeadbeef
    .word   0x3fc00000
    .word   0
