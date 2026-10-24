# F/D 硬浮点裸机短回归：FLW/FLD/FSW/FSD、FMV、FCVT、比较、
# FADD/FSUB/FMUL/FDIV/FSQRT/FMA、FSGNJ、FCLASS、fcsr/fflags、
# mstatus.FS、trap 精确性与 f 寄存器上下文保存。
# 构建：make run64 或 make run32（见同目录 Makefile）。

    .option norvc

    .macro CHECK_F32 reg, expected
    fsw     \reg, 0(s1)
#if __riscv_xlen == 64
    lwu     t0, 0(s1)
#else
    lw      t0, 0(s1)
#endif
    li      t1, \expected
    bne     t0, t1, fail30
    .endm

    .macro CHECK_F64 reg, expected
#if __riscv_flen >= 64
    fsd     \reg, 0(s1)
    ld      t0, 0(s1)
    li      t1, \expected
    bne     t0, t1, fail31
#endif
    .endm

    .section .text.init
    .globl _start
_start:
    la      sp, _stack_top
    la      t0, trap_handler
    csrw    mtvec, t0

    # 清零 .bss
    la      t0, __bss_start
    la      t1, __bss_end
1:
    bgeu    t0, t1, 2f
    sw      zero, 0(t0)
    addi    t0, t0, 4
    j       1b
2:
    li      s1, 0x80007000          # 测试用 scratch

    # FS=3（Dirty），清 fcsr
    li      t0, 0x6000
    csrs    mstatus, t0
    csrw    0x003, zero

    # ---------------------------------------------------------------- FS trap
    li      t0, 0x3fc00000          # 1.5
    fmv.w.x f1, t0
    fsw     f1, 0(s1)
    lw      t0, 0(s1)
    li      t1, 0x3fc00000
    bne     t0, t1, fail40
    csrr    s0, mstatus
    li      t0, 0x6000
    csrc    mstatus, t0             # FS=0
    la      t2, trap_count
    sw      zero, 0(t2)
fs_trap_site:
    flw     f1, 0(s1)               # 应触发 illegal instruction(cause=2)
    lw      t0, 0(t2)
    li      t1, 1
    bne     t0, t1, fail1
    # trap 不应改变 FS（仍为 0）
    csrr    t0, mstatus
    li      t1, 0x6000
    and     t0, t0, t1
    bnez    t0, fail3
    # FS=0 时 fflags/frm/fcsr 也是非法指令
fs_csr_trap_site:
    csrr    t0, 0x001
    lw      t0, 0(t2)
    li      t1, 2
    bne     t0, t1, fail24
    csrw    mstatus, s0             # 恢复 FS=3，之后才能执行 FSW/读 f 寄存器
    # 故障指令不得改变 f1
    fsw     f1, 0(s1)
    lw      t0, 0(s1)
    li      t1, 0x3fc00000
    bne     t0, t1, fail2

    # ------------------------------------------------------- FPR/fcsr context
    li      t0, 0x3fc00000          # 1.5
    fmv.w.x f2, t0
    li      t0, 0xc0700000          # -3.75
    fmv.w.x f3, t0
    li      t0, 0x55                # frm=2(RDN), fflags=0x15
    csrw    0x003, t0
    la      t0, ctx_trap
    csrw    mtvec, t0
    ecall
    la      t0, trap_handler
    csrw    mtvec, t0
    fsw     f2, 0(s1)
    lw      t0, 0(s1)
    li      t1, 0x3fc00000
    bne     t0, t1, fail4
    fsw     f3, 0(s1)
#if __riscv_xlen == 64
    lwu     t0, 0(s1)               # 0xc0700000 在 RV64 必须零扩展比较
#else
    lw      t0, 0(s1)
#endif
    li      t1, 0xc0700000
    bne     t0, t1, fail5
    csrr    t0, 0x003
    li      t1, 0x55
    bne     t0, t1, fail6
    csrw    0x003, zero

    # ---------------------------------------------------------- FLW/FSW + FMV
    li      t0, 0x40490fdb          # pi
    fmv.w.x f4, t0
    fsw     f4, 0(s1)
    flw     f5, 0(s1)
    fsw     f5, 4(s1)
    lw      t0, 4(s1)
    li      t1, 0x40490fdb
    bne     t0, t1, fail7
    # FMV.X.W 对 bit31=1 的值做符号扩展（RV64 检查高 32 位）
    li      t0, 0xbfc00000
    fmv.w.x f5, t0
    fmv.x.w t0, f5
    li      t1, 0xbfc00000
#if __riscv_xlen == 64
    li      t1, 0xffffffffbfc00000
#endif
    bne     t0, t1, fail8

    # ------------------------------------------------------------- FCVT.S.W
    li      t0, 0x40700000          # 3.75
    fmv.w.x f6, t0
    fcvt.w.s t0, f6, rtz
    li      t1, 3
    bne     t0, t1, fail9
    fcvt.w.s t0, f6, rne
    li      t1, 4
    bne     t0, t1, fail10
    li      t0, 0xc0700000          # -3.75
    fmv.w.x f6, t0
    fcvt.w.s t0, f6, rne
    li      t1, -4
    bne     t0, t1, fail11
    li      t0, -1
    fcvt.s.w f6, t0, rne
    fsw     f6, 0(s1)
#if __riscv_xlen == 64
    lwu     t0, 0(s1)               # 0xbf800000 在 RV64 必须零扩展比较
#else
    lw      t0, 0(s1)
#endif
    li      t1, 0xbf800000
    bne     t0, t1, fail12

    # ------------------------------------------------------- 基本算术与比较
    li      t0, 0x3fc00000          # 1.5
    fmv.w.x f7, t0
    li      t0, 0x40100000          # 2.25
    fmv.w.x f8, t0
    fadd.s  f9, f7, f8
    CHECK_F32 f9, 0x40700000       # 3.75
    fsub.s  f9, f7, f8
    CHECK_F32 f9, 0xbf400000       # -0.75
    fmul.s  f9, f7, f8
    CHECK_F32 f9, 0x40580000       # 3.375
    fdiv.s  f9, f7, f8
    CHECK_F32 f9, 0x3f2aaaab       # 0.6666667
    li      t0, 0x40000000          # 2.0
    fmv.w.x f10, t0
    fsqrt.s f9, f10
    CHECK_F32 f9, 0x3fb504f3       # sqrt(2)
    flt.s   t0, f7, f8
    li      t1, 1
    bne     t0, t1, fail13
    fle.s   t0, f8, f7
    bnez    t0, fail14
    feq.s   t0, f7, f7
    li      t1, 1
    bne     t0, t1, fail15

    # --------------------------------------------------------------- FMA
    li      t0, 0x40000000          # 2
    fmv.w.x f11, t0
    li      t0, 0x40400000          # 3
    fmv.w.x f12, t0
    li      t0, 0x40800000          # 4
    fmv.w.x f13, t0
    fmadd.s f14, f11, f12, f13
    CHECK_F32 f14, 0x41200000      # 10
    fmsub.s f14, f11, f12, f13
    CHECK_F32 f14, 0x40000000      # 2
    fnmadd.s f14, f11, f12, f13
    CHECK_F32 f14, 0xc1200000      # -10
    fnmsub.s f14, f11, f12, f13
    CHECK_F32 f14, 0xc0000000      # -2

    # ------------------------------------------------------- FSGNJ/MIN/MAX
    li      t0, 0xbfc00000          # -1.5
    fmv.w.x f15, t0
    li      t0, 0x40200000          # 2.5
    fmv.w.x f16, t0
    fsgnj.s  f17, f15, f16
    CHECK_F32 f17, 0x3fc00000
    fsgnjn.s f17, f15, f16
    CHECK_F32 f17, 0xbfc00000
    fsgnjx.s f17, f15, f16
    CHECK_F32 f17, 0xbfc00000
    li      t0, 0x80000000          # -0
    fmv.w.x f18, t0
    fsw     f18, 0(s1)
#if __riscv_xlen == 64
    lwu     t0, 0(s1)
#else
    lw      t0, 0(s1)
#endif
    li      t1, 0x80000000
    bne     t0, t1, fail23
    li      t0, 0x00000000          # +0
    fmv.w.x f19, t0
    fmin.s  f20, f18, f19
    CHECK_F32 f20, 0x80000000
    fmax.s  f20, f18, f19
    CHECK_F32 f20, 0x00000000

    # ------------------------------------------------------------ FCLASS
    li      t0, 0x7f800000          # +inf
    fmv.w.x f21, t0
    fclass.s t0, f21
    li      t1, 0x80
    bne     t0, t1, fail16
    li      t0, 0x7f800001          # sNaN
    fmv.w.x f21, t0
    fclass.s t0, f21
    li      t1, 0x100
    bne     t0, t1, fail17

    # ---------------------------------------------- NaN/DZ/flags/rounding
    csrw    0x001, zero
    li      t0, 0x7f800000          # +inf
    fmv.w.x f22, t0
    fadd.s  f23, f22, f22
    CHECK_F32 f23, 0x7f800000
    li      t0, 0xff800000          # -inf
    fmv.w.x f22, t0
    li      t0, 0x7f800000
    fmv.w.x f24, t0
    fadd.s  f23, f24, f22
    CHECK_F32 f23, 0x7fc00000      # canonical NaN
    csrr    t0, 0x001
    andi    t0, t0, 0x10
    beqz    t0, fail18

    csrw    0x001, zero
    li      t0, 0x3f800000          # 1.0
    fmv.w.x f25, t0
    fdiv.s  f26, f25, f19          # 1/0
    CHECK_F32 f26, 0x7f800000
    csrr    t0, 0x001
    andi    t0, t0, 0x08
    beqz    t0, fail19

    csrw    0x003, zero
    li      t0, 0x3f800000
    fmv.w.x f25, t0
    li      t0, 0x40400000          # 3.0
    fmv.w.x f27, t0
    fdiv.s  f28, f25, f27, rne
    CHECK_F32 f28, 0x3eaaaaab
    li      t0, 1                   # frm=RTZ
    csrw    0x002, t0
    fdiv.s  f28, f25, f27, dyn
    CHECK_F32 f28, 0x3eaaaaaa
    csrw    0x002, zero
    fdiv.s  f28, f25, f27, rup
    CHECK_F32 f28, 0x3eaaaaab
    fdiv.s  f28, f25, f27, rdn
    CHECK_F32 f28, 0x3eaaaaaa
    csrw    0x003, zero

#if __riscv_flen >= 64
    # ------------------------------------------------------------- D 扩展
    li      t0, 0x3ff8000000000000  # 1.5
    fmv.d.x f1, t0
    li      t0, 0x4002000000000000  # 2.25
    fmv.d.x f2, t0
    fadd.d  f3, f1, f2
    CHECK_F64 f3, 0x400e000000000000
    fsub.d  f3, f1, f2
    CHECK_F64 f3, 0xbfe8000000000000
    fmul.d  f3, f1, f2
    CHECK_F64 f3, 0x400b000000000000
    li      t0, 0x4000000000000000  # 2.0
    fmv.d.x f4, t0
    fsqrt.d f3, f4
    CHECK_F64 f3, 0x3ff6a09e667f3bcd
    li      t0, 0x4000000000000000  # 2
    fmv.d.x f5, t0
    li      t0, 0x4008000000000000  # 3
    fmv.d.x f6, t0
    li      t0, 0x4010000000000000  # 4
    fmv.d.x f7, t0
    fmadd.d f8, f5, f6, f7
    CHECK_F64 f8, 0x4024000000000000
    fnmadd.d f8, f5, f6, f7
    CHECK_F64 f8, 0xc024000000000000
    li      t0, 0x3ff8000000000000  # 1.5
    fmv.d.x f9, t0
    fcvt.s.d f10, f9, rne
    CHECK_F32 f10, 0x3fc00000
    fcvt.d.s f11, f10
    CHECK_F64 f11, 0x3ff8000000000000
    fcvt.l.d t0, f9, rne
    li      t1, 2
    bne     t0, t1, fail20
    li      t0, 0x3ff0000000000000  # 1.0
    fmv.d.x f12, t0
    li      t0, 0x4008000000000000  # 3.0
    fmv.d.x f13, t0
    fdiv.d  f14, f12, f13, rne
    CHECK_F64 f14, 0x3fd5555555555555
    li      t0, 0x7ff0000000000001  # sNaN（quiet 位 bit51=0）
    fmv.d.x f15, t0
    fclass.d t0, f15
    li      t1, 0x100
    bne     t0, t1, fail21
    # FLD/FSD roundtrip
    fsd     f14, 0(s1)
    fld     f16, 0(s1)
    CHECK_F64 f16, 0x3fd5555555555555
#endif

    # ------------------------------------------------------------- C 代码
    call    fpu_c_test
    bnez    a0, fail22

    li      a0, 0
    .word   0x0000000b

# ------------------------------------------------------------------ helpers
# ------------------------------------------------------------- trap handlers
    .align  2
trap_handler:
    csrr    t0, mcause
    li      t1, 2                   # illegal instruction
    bne     t0, t1, fail32
    csrr    t0, mepc
    addi    t0, t0, 4
    csrw    mepc, t0
    la      t2, trap_count
    lw      t3, 0(t2)
    addi    t3, t3, 1
    sw      t3, 0(t2)
    mret

    .align  2
ctx_trap:
    csrr    t0, mcause
    li      t1, 11                  # M-mode ecall
    bne     t0, t1, fail33
    la      t1, ctx_save
#if __riscv_flen >= 64
    fsd     f2, 0(t1)
    fsd     f3, 8(t1)
#else
    fsw     f2, 0(t1)
    fsw     f3, 4(t1)
#endif
    li      t2, 0x3f800000          # 破坏 f2/f3
    fmv.w.x f2, t2
    fmv.w.x f3, t2
#if __riscv_flen >= 64
    fld     f2, 0(t1)
    fld     f3, 8(t1)
#else
    flw     f2, 0(t1)
    flw     f3, 4(t1)
#endif
    csrr    t0, mepc
    addi    t0, t0, 4
    csrw    mepc, t0
    mret

# ------------------------------------------------------------------- fail
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
fail30: li a0, 30; .word 0x0000000b
fail31: li a0, 31; .word 0x0000000b
fail32: li a0, 32; .word 0x0000000b
fail33: li a0, 33; .word 0x0000000b
fail40: li a0, 40; .word 0x0000000b

    .section .bss
    .align  4
trap_count: .space 4
    .align  3
ctx_save:   .space 16
