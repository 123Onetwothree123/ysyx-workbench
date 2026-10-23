# RV64IMAFDC 定向测试：D 算术、FMA、NaN、舍入、flags、转换、mstatus.FS。
# 成功时 a0=0 并命中 NEMU trap；失败时 a0=失败编号。

# CHECK 宏：比较寄存器与常量，失败时以唯一编号退出。
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

.macro LOAD_D freg, bits
    li      t0, \bits
    fmv.d.x \freg, t0
.endm

    .section .text
    .globl _start
_start:
    li      sp, 0x87fffff0              # NEMU 默认 128MB 内存顶端
    # mstatus.FS = Initial（bit14:13 = 01）
    li      t0, 0x2000
    csrs    mstatus, t0

    # misa 必须同时宣告 F(bit5) 与 D(bit3)
    csrr    t0, misa
    li      t1, 0x0014112d
    and     t0, t0, t1
    CHECK_R t0, t1, 1

    # FS 仍为 Initial
    csrr    t0, mstatus
    srli    t0, t0, 13
    andi    t0, t0, 3
    CHECK   t0, 1, 2

    # 1.5 + 2.5 = 4.0，同时 FS 变为 Dirty、SD=1
    LOAD_D  ft0, 0x3ff8000000000000
    LOAD_D  ft1, 0x4004000000000000
    fadd.d  ft2, ft0, ft1
    fmv.x.d a0, ft2
    CHECK   a0, 0x4010000000000000, 3
    csrr    t0, mstatus
    srli    t1, t0, 13
    andi    t1, t1, 3
    CHECK   t1, 3, 4
    srli    t0, t0, 63
    CHECK   t0, 1, 5

    # sstatus 视图必须同样反映 FS=3 与 SD
    csrr    t0, sstatus
    srli    t1, t0, 13
    andi    t1, t1, 3
    CHECK   t1, 3, 89
    srli    t0, t0, 63
    CHECK   t0, 1, 90

    # FSUB/FMUL/FDIV/FSQRT.D
    fsub.d  ft2, ft1, ft0
    fmv.x.d a0, ft2
    CHECK   a0, 0x3ff0000000000000, 6
    fmul.d  ft2, ft0, ft1
    fmv.x.d a0, ft2
    CHECK   a0, 0x400e000000000000, 7
    LOAD_D  ft3, 0x4022000000000000      # 9.0
    fsqrt.d ft2, ft3
    fmv.x.d a0, ft2
    CHECK   a0, 0x4008000000000000, 8
    LOAD_D  ft3, 0x4000000000000000      # 2.0
    fdiv.d  ft2, ft3, ft3
    fmv.x.d a0, ft2
    CHECK   a0, 0x3ff0000000000000, 9

    # FDIV 1/0 = +inf，DZ(bit3)=1
    csrw    fflags, zero
    LOAD_D  ft3, 0
    fdiv.d  ft2, ft0, ft3
    fmv.x.d a0, ft2
    CHECK   a0, 0x7ff0000000000000, 10
    csrr    t0, fflags
    CHECK   t0, 0x08, 11

    # 1.5*2.0+0.25 = 3.25（FMA 只舍入一次）
    LOAD_D  ft1, 0x4000000000000000      # 2.0
    LOAD_D  ft3, 0x3fd0000000000000      # 0.25
    fmadd.d ft2, ft0, ft1, ft3
    fmv.x.d a0, ft2
    CHECK   a0, 0x400a000000000000, 12
    fmsub.d ft2, ft0, ft1, ft3
    fmv.x.d a0, ft2
    CHECK   a0, 0x4006000000000000, 13
    fnmsub.d ft2, ft0, ft1, ft3
    fmv.x.d a0, ft2
    CHECK   a0, 0xc006000000000000, 14
    fnmadd.d ft2, ft0, ft1, ft3
    fmv.x.d a0, ft2
    CHECK   a0, 0xc00a000000000000, 15

    # 真正融合：1+2^-52 与 1-2^-53 的乘积减 1 必须保留 2^-105 的尾部；
    # 若先做乘法舍入会得到 0。
    LOAD_D  ft0, 0x3ff0000000000001
    LOAD_D  ft1, 0x3fefffffffffffff
    LOAD_D  ft3, 0xbff0000000000000
    fmadd.d ft2, ft0, ft1, ft3
    fmv.x.d a0, ft2
    CHECK   a0, 0x3c9ffffffffffffe, 88

    # FMIN/FMAX 数值与 ±0
    LOAD_D  ft0, 0x3ff8000000000000      # 1.5
    LOAD_D  ft1, 0x4000000000000000      # 2.0
    fmin.d  ft2, ft0, ft1
    fmv.x.d a0, ft2
    CHECK   a0, 0x3ff8000000000000, 16
    fmax.d  ft2, ft0, ft1
    fmv.x.d a0, ft2
    CHECK   a0, 0x4000000000000000, 17
    fmv.d.x ft0, zero                    # +0
    LOAD_D  ft1, 0x8000000000000000      # -0
    fmin.d  ft2, ft0, ft1
    fmv.x.d a0, ft2
    CHECK   a0, 0x8000000000000000, 18
    fmax.d  ft2, ft0, ft1
    fmv.x.d a0, ft2
    CHECK   a0, 0, 19

    # qNaN + 1.0 -> canonical NaN，无 NV；sNaN -> canonical + NV
    csrw    fflags, zero
    LOAD_D  ft0, 0x7ff8000000000001
    LOAD_D  ft1, 0x3ff0000000000000
    fadd.d  ft2, ft0, ft1
    fmv.x.d a0, ft2
    CHECK   a0, 0x7ff8000000000000, 20
    csrr    t0, fflags
    CHECK   t0, 0, 21
    csrw    fflags, zero
    LOAD_D  ft0, 0x7ff0000000000001
    fadd.d  ft2, ft0, ft1
    fmv.x.d a0, ft2
    CHECK   a0, 0x7ff8000000000000, 22
    csrr    t0, fflags
    CHECK   t0, 0x10, 23

    # FSQRT(-1) -> canonical NaN + NV
    csrw    fflags, zero
    LOAD_D  ft0, 0xbff0000000000000
    fsqrt.d ft2, ft0
    fmv.x.d a0, ft2
    CHECK   a0, 0x7ff8000000000000, 24
    csrr    t0, fflags
    CHECK   t0, 0x10, 25

    # FMIN(NaN,1)=1 无 flag；FMIN(sNaN,1)=1 且 NV；两个 qNaN -> canonical
    csrw    fflags, zero
    LOAD_D  ft0, 0x7ff8000000000001
    LOAD_D  ft1, 0x3ff0000000000000
    fmin.d  ft2, ft0, ft1
    fmv.x.d a0, ft2
    CHECK   a0, 0x3ff0000000000000, 26
    csrr    t0, fflags
    CHECK   t0, 0, 27
    csrw    fflags, zero
    LOAD_D  ft0, 0x7ff0000000000001
    fmin.d  ft2, ft0, ft1
    fmv.x.d a0, ft2
    CHECK   a0, 0x3ff0000000000000, 28
    csrr    t0, fflags
    CHECK   t0, 0x10, 29
    csrw    fflags, zero
    LOAD_D  ft0, 0x7ff8000000000001
    LOAD_D  ft1, 0x7ff8000000000002
    fmax.d  ft2, ft0, ft1
    fmv.x.d a0, ft2
    CHECK   a0, 0x7ff8000000000000, 30
    csrr    t0, fflags
    CHECK   t0, 0, 31

    # 舍入模式：1.0 + 2^-53
    LOAD_D  ft0, 0x3ff0000000000000
    LOAD_D  ft1, 0x3ca0000000000000
    csrw    frm, zero                    # RNE
    csrw    fflags, zero
    fadd.d  ft2, ft0, ft1
    fmv.x.d a0, ft2
    CHECK   a0, 0x3ff0000000000000, 32
    csrr    t0, fflags
    CHECK   t0, 0x01, 33                # NX
    li      t0, 1                        # RTZ
    csrw    frm, t0
    fadd.d  ft2, ft0, ft1
    fmv.x.d a0, ft2
    CHECK   a0, 0x3ff0000000000000, 34
    li      t0, 2                        # RDN
    csrw    frm, t0
    fadd.d  ft2, ft0, ft1
    fmv.x.d a0, ft2
    CHECK   a0, 0x3ff0000000000000, 35
    li      t0, 3                        # RUP
    csrw    frm, t0
    fadd.d  ft2, ft0, ft1
    fmv.x.d a0, ft2
    CHECK   a0, 0x3ff0000000000001, 36
    li      t0, 4                        # RMM
    csrw    frm, t0
    fadd.d  ft2, ft0, ft1
    fmv.x.d a0, ft2
    CHECK   a0, 0x3ff0000000000001, 37
    csrw    frm, zero

    # 溢出：DBL_MAX * 2 -> +inf，OF|NX = 0x5
    csrw    fflags, zero
    LOAD_D  ft0, 0x7fefffffffffffff
    LOAD_D  ft1, 0x4000000000000000
    fmul.d  ft2, ft0, ft1
    fmv.x.d a0, ft2
    CHECK   a0, 0x7ff0000000000000, 84
    csrr    t0, fflags
    CHECK   t0, 0x05, 85

    # 下溢：最小 subnormal / 2 -> +0，UF|NX = 0x3（舍入后检测 tininess）
    csrw    fflags, zero
    LOAD_D  ft0, 0x0000000000000001
    LOAD_D  ft1, 0x4000000000000000
    fdiv.d  ft2, ft0, ft1
    fmv.x.d a0, ft2
    CHECK   a0, 0, 86
    csrr    t0, fflags
    CHECK   t0, 0x03, 87

    # 比较：qNaN 的 FEQ 不报 NV，FLT/FLE 报 NV
    csrw    fflags, zero
    LOAD_D  ft0, 0x7ff8000000000000
    LOAD_D  ft1, 0x3ff0000000000000
    feq.d   a0, ft0, ft1
    CHECK   a0, 0, 38
    csrr    t0, fflags
    CHECK   t0, 0, 39
    csrw    fflags, zero
    flt.d   a0, ft0, ft1
    CHECK   a0, 0, 40
    csrr    t0, fflags
    CHECK   t0, 0x10, 41
    csrw    fflags, zero
    fle.d   a0, ft0, ft1
    CHECK   a0, 0, 42
    csrr    t0, fflags
    CHECK   t0, 0x10, 43

    # FSGNJ/N/X
    LOAD_D  ft0, 0x3ff0000000000000
    LOAD_D  ft1, 0x8000000000000000
    fsgnj.d ft2, ft0, ft1
    fmv.x.d a0, ft2
    CHECK   a0, 0xbff0000000000000, 44
    fsgnjn.d ft2, ft0, ft1
    fmv.x.d a0, ft2
    CHECK   a0, 0x3ff0000000000000, 45
    fsgnjx.d ft2, ft0, ft1
    fmv.x.d a0, ft2
    CHECK   a0, 0xbff0000000000000, 46

    # FCLASS.D
    LOAD_D  ft0, 0x7ff0000000000000
    fclass.d a0, ft0
    CHECK   a0, 0x80, 47
    LOAD_D  ft0, 0xfff0000000000000
    fclass.d a0, ft0
    CHECK   a0, 0x01, 48
    fmv.d.x ft0, zero
    fclass.d a0, ft0
    CHECK   a0, 0x10, 49
    LOAD_D  ft0, 0x8000000000000000
    fclass.d a0, ft0
    CHECK   a0, 0x08, 50
    LOAD_D  ft0, 0x7ff0000000000001
    fclass.d a0, ft0
    CHECK   a0, 0x100, 51
    LOAD_D  ft0, 0x7ff8000000000000
    fclass.d a0, ft0
    CHECK   a0, 0x200, 52

    # 转换
    LOAD_D  ft0, 0x400d99999999999a      # 3.7
    csrw    frm, zero
    fcvt.w.d a0, ft0
    CHECK   a0, 4, 53
    fcvt.w.d a0, ft0, rtz
    CHECK   a0, 3, 54
    csrw    fflags, zero
    LOAD_D  ft0, 0xbff0000000000000      # -1.0
    fcvt.wu.d a0, ft0
    CHECK   a0, 0, 55
    csrr    t0, fflags
    CHECK   t0, 0x10, 56
    LOAD_D  ft0, 0x4016000000000000      # 5.5
    fcvt.l.d a0, ft0, rtz
    CHECK   a0, 5, 57
    li      t0, -7
    fcvt.d.w ft0, t0
    fmv.x.d a0, ft0
    CHECK   a0, 0xc01c000000000000, 58
    li      t0, -1
    fcvt.d.lu ft0, t0
    fmv.x.d a0, ft0
    CHECK   a0, 0x43f0000000000000, 59
    LOAD_D  ft0, 0x3ff8000000000000      # 1.5
    fcvt.s.d ft0, ft0
    fsd     ft0, 0(sp)
    ld      a0, 0(sp)
    CHECK   a0, 0xffffffff3fc00000, 60  # 单精度结果必须 NaN-boxed
    fcvt.d.s ft0, ft0
    fmv.x.d a0, ft0
    CHECK   a0, 0x3ff8000000000000, 61

    # FMV.W.X + 非 boxed 的 .S 输入 -> canonical NaN
    li      t0, 0x3fc00000
    fmv.w.x ft0, t0
    fsd     ft0, 0(sp)
    ld      a0, 0(sp)
    CHECK   a0, 0xffffffff3fc00000, 62
    li      t0, 0x000000003fc00000
    fmv.d.x ft0, t0
    csrw    fflags, zero
    fadd.s  ft2, ft0, ft0
    fsd     ft2, 0(sp)
    ld      a0, 0(sp)
    CHECK   a0, 0xffffffff7fc00000, 63
    csrr    t0, fflags
    CHECK   t0, 0, 64

    # FLD/FSD
    la      t0, ddata
    fld     ft0, 0(t0)
    fmv.x.d a0, ft0
    CHECK   a0, 0x400921fb54442d18, 65
    la      t0, ddata
    fld     ft1, 8(t0)
    fsd     ft1, 16(t0)
    ld      a0, 16(t0)
    CHECK   a0, 0xc00921fb54442d18, 66

    # FLW/FSW（含 FSW 只写低 32 位）
    la      t0, ddata
    flw     ft0, 24(t0)
    fsd     ft0, 0(sp)
    ld      a0, 0(sp)
    CHECK   a0, 0xffffffff40490fdb, 67
    la      t0, ddata
    li      t1, 0x12345678
    fsw     ft0, 28(t0)
    lw      a0, 28(t0)
    CHECK   a0, 0x40490fdb, 68

    # mstatus.FS=Off 时执行浮点指令必须非法指令陷入；trap 保存/恢复不破坏 FS
    la      t0, fs_handler
    csrw    mtvec, t0
    fmv.x.d s0, ft0                     # 保存 ft0 原始值（不依赖浮点）
    li      t0, 0x6000
    csrc    mstatus, t0                 # FS=Off
    la      t1, fs_off_inst
fs_off_inst:
    fadd.d  ft0, ft0, ft0               # 应触发 cause=2
    j       fail_fs                     # 正常不应到这里
fs_resume:
    # handler 已把 FS 恢复为 Initial 并跳过故障指令；这里执行 FP 后应为 Dirty
    fadd.d  ft2, ft1, ft1
    csrr    t0, mstatus
    srli    t0, t0, 13
    andi    t0, t0, 3
    CHECK   t0, 3, 69

    # 非法动态舍入模式：frm=5 时 fadd.d 必须陷入
    la      t0, rm_handler
    csrw    mtvec, t0
    li      t0, 5
    csrw    frm, t0
    la      t1, bad_rm_inst
bad_rm_inst:
    fadd.d  ft0, ft0, ft0
    j       fail_rm
rm_resume:
    csrw    frm, zero

    # FLD/FSD 非对齐访问：cause=4/6，mtval=地址
    la      t0, align_handler
    csrw    mtvec, t0
    la      t1, align_bad
align_load_inst:
    fld     ft0, 1(t1)
    j       fail_align_load
align_load_resume:
    la      t1, align_bad
align_store_inst:
    fsd     ft0, 3(t1)
    j       fail_align_store
align_store_resume:

    li      a0, 0
    .word   0x0000000b

# ---- trap handlers ----
    .align  2
fs_handler:
    csrr    t0, mcause
    CHECK   t0, 2, 70
    csrr    t0, mepc
    la      t1, fs_off_inst
    CHECK_R t0, t1, 71
    csrr    t0, mtval
    # mtval 是 32 位指令编码，低 32 位应非零
    bnez    t0, 1f
    li      a0, 72
    .word   0x0000000b
1:
    # 故障指令不能改动 ft0（FS=Off 时 handler 内也不能执行 FMV.X.D，
    # 因此使用进入 trap 前保存的整数副本 s0）
    mv      a0, s0
    CHECK   a0, 0xffffffff40490fdb, 73
    li      t0, 0x2000                 # FS=Initial
    csrs    mstatus, t0
    # 跳过故障 fadd 及其后的 j fail_fs，直接回到 fs_resume
    la      t0, fs_resume
    csrw    mepc, t0
    mret

    .align  2
rm_handler:
    csrr    t0, mcause
    CHECK   t0, 2, 74
    csrr    t0, mepc
    la      t1, bad_rm_inst
    CHECK_R t0, t1, 75
    csrw    frm, zero
    # 跳过故障 fadd 和紧随其后的 j fail_rm
    la      t0, rm_resume
    csrw    mepc, t0
    mret

    .align  2
align_handler:
    csrr    t0, mcause
    csrr    t2, mepc
    la      t3, align_load_inst
    beq     t2, t3, 1f
    # 当前是 store：cause=6，mtval=base+3
    CHECK   t0, 6, 76
    csrr    t0, mtval
    la      t1, align_bad
    addi    t1, t1, 3
    CHECK_R t0, t1, 78
    la      t4, align_store_resume
    j       2f
1:  # load：cause=4，mtval=base+1
    CHECK   t0, 4, 77
    csrr    t0, mtval
    la      t1, align_bad
    addi    t1, t1, 1
    CHECK_R t0, t1, 79
    la      t4, align_load_resume
2:
    # 跳过故障访存及其后的 j fail_align_*
    csrw    mepc, t4
    mret

fail_fs:
    li      a0, 80
    .word   0x0000000b
fail_rm:
    li      a0, 81
    .word   0x0000000b
fail_align_load:
    li      a0, 82
    .word   0x0000000b
fail_align_store:
    li      a0, 83
    .word   0x0000000b

    .section .data
    .align  3
ddata:
    .quad   0x400921fb54442d18          # 3.141592653589793
    .quad   0xc00921fb54442d18          # -3.141592653589793
    .quad   0x1122334455667788
    .word   0x40490fdb                  # 3.14159274f
    .word   0xdeadbeef
    .align  3
align_bad:
    .quad   0x0123456789abcdef
