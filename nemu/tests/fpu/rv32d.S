# RV32IMAFDC 定向测试：32 位 D 算术/访存、F 单精度、FMA、NaN、舍入、flags。
# RV32 的 FLD/FSD 由 NEMU 拆成两个 32 位对齐字访问，这里专门覆盖。
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

# 64 位浮点常量通过 .data 中的 scratch 装载（RV32 的 li 只有 32 位）。
.macro LOAD_D freg, hi, lo
    la      t0, scratch
    li      t1, \lo
    sw      t1, 0(t0)
    li      t1, \hi
    sw      t1, 4(t0)
    fld     \freg, 0(t0)
.endm

# 把 f 寄存器按原始位型存入栈，结果低/高字在 a0/a1。
.macro STORE_D freg
    fsd     \freg, 0(sp)
    lw      a0, 0(sp)
    lw      a1, 4(sp)
.endm

.macro CHECK_D hi, lo, code
    li      t6, \lo
    beq     a0, t6, 8f
    li      a0, \code
    .word   0x0000000b
8:
    li      t6, \hi
    beq     a1, t6, 9f
    li      a0, \code
    .word   0x0000000b
9:
.endm

_start:
    li      sp, 0x87fffff0
    li      t0, 0x2000                 # mstatus.FS=Initial
    csrs    mstatus, t0

    # misa 宣告 F/D/C/A
    csrr    t0, misa
    li      t1, 0x0014112d
    and     t0, t0, t1
    CHECK_R t0, t1, 1

    # ---- F 单精度 ----
    li      t0, 0x3fc00000             # 1.5f
    fmv.w.x ft0, t0
    li      t0, 0x40200000             # 2.5f
    fmv.w.x ft1, t0
    fadd.s  ft2, ft0, ft1
    fmv.x.w a0, ft2
    CHECK   a0, 0x40800000, 2          # 4.0f
    fsub.s  ft2, ft1, ft0
    fmv.x.w a0, ft2
    CHECK   a0, 0x3f800000, 3
    fmul.s  ft2, ft0, ft1
    fmv.x.w a0, ft2
    CHECK   a0, 0x40700000, 4          # 3.75f
    li      t0, 0x41100000             # 9.0f
    fmv.w.x ft3, t0
    fsqrt.s ft2, ft3
    fmv.x.w a0, ft2
    CHECK   a0, 0x40400000, 5          # 3.0f
    csrw    fflags, zero
    fmv.w.x ft4, zero
    fdiv.s  ft2, ft3, ft4              # 9/0 = +inf, DZ
    fmv.x.w a0, ft2
    CHECK   a0, 0x7f800000, 6
    csrr    t0, fflags
    CHECK   t0, 0x08, 7

    # FMA.S：1.5*2.5+0.25 = 4.0
    li      t0, 0x3e800000             # 0.25f
    fmv.w.x ft3, t0
    fmadd.s ft2, ft0, ft1, ft3
    fmv.x.w a0, ft2
    CHECK   a0, 0x40800000, 8
    fmsub.s ft2, ft0, ft1, ft3
    fmv.x.w a0, ft2
    CHECK   a0, 0x40600000, 9          # 3.5f
    fnmadd.s ft2, ft0, ft1, ft3
    fmv.x.w a0, ft2
    CHECK   a0, 0xc0800000, 10
    fnmsub.s ft2, ft0, ft1, ft3
    fmv.x.w a0, ft2
    CHECK   a0, 0xc0600000, 11

    # qNaN/sNaN .S
    csrw    fflags, zero
    li      t0, 0x7fc00001
    fmv.w.x ft0, t0
    li      t0, 0x3f800000
    fmv.w.x ft1, t0
    fadd.s  ft2, ft0, ft1
    fmv.x.w a0, ft2
    CHECK   a0, 0x7fc00000, 12
    csrr    t0, fflags
    CHECK   t0, 0, 13
    csrw    fflags, zero
    li      t0, 0x7f800001
    fmv.w.x ft0, t0
    fadd.s  ft2, ft0, ft1
    fmv.x.w a0, ft2
    CHECK   a0, 0x7fc00000, 14
    csrr    t0, fflags
    CHECK   t0, 0x10, 15

    # 单精度舍入：1.0f + 2^-24 = 半 ULP
    li      t0, 0x3f800000
    fmv.w.x ft0, t0
    li      t0, 0x33800000             # 2^-24
    fmv.w.x ft1, t0
    csrw    frm, zero                   # RNE -> 1.0
    fadd.s  ft2, ft0, ft1
    fmv.x.w a0, ft2
    CHECK   a0, 0x3f800000, 16
    li      t0, 3                        # RUP -> 1+2^-23
    csrw    frm, t0
    fadd.s  ft2, ft0, ft1
    fmv.x.w a0, ft2
    CHECK   a0, 0x3f800001, 17
    li      t0, 2                        # RDN -> 1.0
    csrw    frm, t0
    fadd.s  ft2, ft0, ft1
    fmv.x.w a0, ft2
    CHECK   a0, 0x3f800000, 18
    li      t0, 4                        # RMM -> 1+2^-23
    csrw    frm, t0
    fadd.s  ft2, ft0, ft1
    fmv.x.w a0, ft2
    CHECK   a0, 0x3f800001, 19
    csrw    frm, zero

    # FMIN/FMAX + ±0
    li      t0, 0x3f800000
    fmv.w.x ft0, t0
    li      t0, 0x40000000
    fmv.w.x ft1, t0
    fmin.s  ft2, ft0, ft1
    fmv.x.w a0, ft2
    CHECK   a0, 0x3f800000, 20
    fmax.s  ft2, ft0, ft1
    fmv.x.w a0, ft2
    CHECK   a0, 0x40000000, 21
    fmv.w.x ft0, zero
    li      t0, 0x80000000
    fmv.w.x ft1, t0
    fmin.s  ft2, ft0, ft1
    fmv.x.w a0, ft2
    CHECK   a0, 0x80000000, 22
    fmax.s  ft2, ft0, ft1
    fmv.x.w a0, ft2
    CHECK   a0, 0, 23

    # FCLASS.S / FSGNJ.S
    li      t0, 0x7f800000
    fmv.w.x ft0, t0
    fclass.s a0, ft0
    CHECK   a0, 0x80, 24
    li      t0, 0x7f800001
    fmv.w.x ft0, t0
    fclass.s a0, ft0
    CHECK   a0, 0x100, 25
    li      t0, 0x3f800000
    fmv.w.x ft0, t0
    li      t0, 0x80000000
    fmv.w.x ft1, t0
    fsgnj.s ft2, ft0, ft1
    fmv.x.w a0, ft2
    CHECK   a0, 0xbf800000, 26

    # 比较与 NaN 标志
    li      t0, 0x7fc00000
    fmv.w.x ft0, t0
    li      t0, 0x3f800000
    fmv.w.x ft1, t0
    csrw    fflags, zero
    feq.s   a0, ft0, ft1
    CHECK   a0, 0, 27
    csrr    t0, fflags
    CHECK   t0, 0, 28
    csrw    fflags, zero
    flt.s   a0, ft0, ft1
    CHECK   a0, 0, 29
    csrr    t0, fflags
    CHECK   t0, 0x10, 30

    # 整数/浮点转换
    li      t0, 0x40600000             # 3.5f
    fmv.w.x ft0, t0
    fcvt.w.s a0, ft0
    CHECK   a0, 4, 31
    fcvt.w.s a0, ft0, rtz
    CHECK   a0, 3, 32
    csrw    fflags, zero
    li      t0, 0xbf800000             # -1.0f
    fmv.w.x ft0, t0
    fcvt.wu.s a0, ft0
    CHECK   a0, 0, 33
    csrr    t0, fflags
    CHECK   t0, 0x10, 34
    li      t0, -7
    fcvt.s.w ft0, t0
    fmv.x.w a0, ft0
    CHECK   a0, 0xc0e00000, 35         # -7.0f
    li      t0, -1
    fcvt.s.wu ft0, t0
    fmv.x.w a0, ft0
    CHECK   a0, 0x4f800000, 36         # 2^32

    # ---- D 双精度（RV32 FLEN=64）----
    LOAD_D  ft0, 0x3ff00000, 0x00000000  # 1.0
    LOAD_D  ft1, 0x40000000, 0x00000000  # 2.0
    fadd.d  ft2, ft0, ft1
    STORE_D ft2
    CHECK_D 0x40080000, 0x00000000, 37   # 3.0
    fmul.d  ft2, ft0, ft1
    STORE_D ft2
    CHECK_D 0x40000000, 0x00000000, 38
    LOAD_D  ft3, 0x40220000, 0x00000000  # 9.0
    fsqrt.d ft2, ft3
    STORE_D ft2
    CHECK_D 0x40080000, 0x00000000, 39

    # FMA.D：1.5*2.0+0.25=3.25
    LOAD_D  ft0, 0x3ff80000, 0x00000000  # 1.5
    LOAD_D  ft1, 0x40000000, 0x00000000  # 2.0
    LOAD_D  ft3, 0x3fd00000, 0x00000000  # 0.25
    fmadd.d ft2, ft0, ft1, ft3
    STORE_D ft2
    CHECK_D 0x400a0000, 0x00000000, 40   # 3.25

    # qNaN/sNaN .D
    csrw    fflags, zero
    LOAD_D  ft0, 0x7ff80000, 0x00000001
    LOAD_D  ft1, 0x3ff00000, 0x00000000
    fadd.d  ft2, ft0, ft1
    STORE_D ft2
    CHECK_D 0x7ff80000, 0x00000000, 41
    csrr    t0, fflags
    CHECK   t0, 0, 42
    csrw    fflags, zero
    LOAD_D  ft0, 0x7ff00000, 0x00000001
    fadd.d  ft2, ft0, ft1
    STORE_D ft2
    CHECK_D 0x7ff80000, 0x00000000, 43
    csrr    t0, fflags
    CHECK   t0, 0x10, 44

    # 双精度舍入：1.0 + 2^-53
    LOAD_D  ft0, 0x3ff00000, 0x00000000
    LOAD_D  ft1, 0x3ca00000, 0x00000000
    csrw    frm, zero
    fadd.d  ft2, ft0, ft1
    STORE_D ft2
    CHECK_D 0x3ff00000, 0x00000000, 45
    li      t0, 3
    csrw    frm, t0
    fadd.d  ft2, ft0, ft1
    STORE_D ft2
    CHECK_D 0x3ff00000, 0x00000001, 46
    csrw    frm, zero

    # D 比较与转换
    LOAD_D  ft0, 0x7ff80000, 0x00000000
    LOAD_D  ft1, 0x3ff00000, 0x00000000
    csrw    fflags, zero
    feq.d   a0, ft0, ft1
    CHECK   a0, 0, 47
    csrr    t0, fflags
    CHECK   t0, 0, 48
    LOAD_D  ft0, 0x400d9999, 0x9999999a  # 3.7
    fcvt.w.d a0, ft0
    CHECK   a0, 4, 49
    fcvt.w.d a0, ft0, rtz
    CHECK   a0, 3, 50
    LOAD_D  ft0, 0xbff00000, 0x00000000  # -1.0
    csrw    fflags, zero
    fcvt.wu.d a0, ft0
    CHECK   a0, 0, 51
    csrr    t0, fflags
    CHECK   t0, 0x10, 52
    li      t0, -7
    fcvt.d.w ft0, t0
    STORE_D ft0
    CHECK_D 0xc01c0000, 0x00000000, 53
    li      t0, -1
    fcvt.d.wu ft0, t0
    STORE_D ft0
    CHECK_D 0x41efffff, 0xffe00000, 54   # (double)0xffffffffu

    # FCVT.S.D / FCVT.D.S 与 NaN boxing
    LOAD_D  ft0, 0x3ff80000, 0x00000000
    fcvt.s.d ft0, ft0
    STORE_D ft0
    CHECK_D 0xffffffff, 0x3fc00000, 55
    fcvt.d.s ft0, ft0
    STORE_D ft0
    CHECK_D 0x3ff80000, 0x00000000, 56

    # 非 NaN-boxed 的 .S 输入按 canonical NaN 处理
    la      t0, scratch
    li      t1, 0x3fc00000
    sw      t1, 0(t0)
    sw      zero, 4(t0)
    fld     ft0, 0(t0)
    csrw    fflags, zero
    fadd.s  ft2, ft0, ft0
    STORE_D ft2
    CHECK_D 0xffffffff, 0x7fc00000, 57
    csrr    t0, fflags
    CHECK   t0, 0, 58

    # FLD/FSD 使用两个 32 位字，且高字正确
    la      t0, ddata
    fld     ft0, 0(t0)
    STORE_D ft0
    CHECK_D 0x400921fb, 0x54442d18, 59
    fld     ft1, 8(t0)
    la      t1, scratch
    fsd     ft1, 0(t1)
    lw      a0, 0(t1)
    lw      a1, 4(t1)
    CHECK_D 0xc00921fb, 0x54442d18, 60

    # FLW/FSW
    la      t0, ddata
    flw     ft0, 16(t0)
    STORE_D ft0
    CHECK_D 0xffffffff, 0x40490fdb, 61
    fsw     ft0, 20(t0)
    lw      a0, 20(t0)
    CHECK   a0, 0x40490fdb, 62

    # mstatus.FS=Off -> 浮点指令非法；trap 恢复 FS 后继续
    la      t0, fs_handler
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
    CHECK   t0, 3, 63

    li      a0, 0
    .word   0x0000000b

    .align  2
fs_handler:
    csrr    t0, mcause
    CHECK   t0, 2, 64
    csrr    t0, mepc
    la      t1, fs_off_inst
    CHECK_R t0, t1, 65
    li      t0, 0x2000
    csrs    mstatus, t0
    la      t0, fs_resume
    csrw    mepc, t0
    mret

fail_fs:
    li      a0, 66
    .word   0x0000000b

    .section .data
    .align  3
scratch:
    .quad   0
ddata:
    .quad   0x400921fb54442d18
    .quad   0xc00921fb54442d18
    .word   0x40490fdb
    .word   0xdeadbeef
