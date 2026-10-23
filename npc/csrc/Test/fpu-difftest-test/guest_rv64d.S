# RV64D 正向裸机镜像。
#
# 覆盖：
#   * FLW/FSW/FLD/FSD、单/双精度访存与小端字节序；
#   * FADD/FSUB/FMUL/FDIV/FSQRT（含 NaN、舍入、flags）；
#   * FMADD/FMSUB/FNMSUB/FNMADD；
#   * FMIN/FMAX（±0、NaN）、FSGNJ/FSGNJN/FSGNJX；
#   * FEQ/FLT/FLE（qNaN/sNaN）；
#   * FCVT（W/WU/L/LU、S<->D、溢出/下溢）、FMV、FCLASS；
#   * fcsr 舍入模式与 sticky flags、mstatus.FS。
#
# 所有期望值都由整数寄存器比较完成；即使 REF 没有比较 FPR，DUT 的浮点
# 结果错误也会通过 GPR/PC 在 DiffTest 中暴露。
.section .data
.align 3
buf:        .zero 32
trap_cause: .word 0
trap_site:  .word 0

.section .text
.globl _start
# 检查宏：t4/t5 临时使用，失败时跳到独立 failN。
.macro CHECK_S freg, expected, code
    fmv.x.w t4, \freg
    slli t4, t4, 32
    srli t4, t4, 32
    li t5, \expected
    beq t4, t5, 8f
    li a0, \code
    j halt
8:
.endm

.macro CHECK_D freg, expected, code
    fmv.x.d t4, \freg
    li t5, \expected
    beq t4, t5, 9f
    li a0, \code
    j halt
9:
.endm

_start:
    la t0, trap_entry
    csrw mtvec, t0

    # FS=Initial(1)，执行浮点后应自动变 Dirty(3)。
    li t0, 0x2000
    csrs mstatus, t0
    csrr t0, mstatus
    li t1, 0x6000
    and t0, t0, t1
    li t1, 0x2000
    bne t0, t1, fail1

    csrw fcsr, zero

    # ---------------------------------------------------------------
    # FLW / FSW
    # ---------------------------------------------------------------
    la s0, buf
    li t0, 0x3fc00000          # 1.5f
    sw t0, 0(s0)
    flw ft0, 0(s0)
    fmv.x.w t1, ft0
    slli t1, t1, 32
    srli t1, t1, 32
    li t2, 0x3fc00000
    bne t1, t2, fail2

    li t0, 0x40100000          # 2.25f
    fmv.w.x ft1, t0
    fsw ft1, 4(s0)
    lw t1, 4(s0)
    li t2, 0x40100000
    bne t1, t2, fail3

    # ---------------------------------------------------------------
    # FLD / FSD + 小端字节序
    # ---------------------------------------------------------------
    li t0, 0x4004000000000000  # 2.5
    sd t0, 8(s0)
    fld ft0, 8(s0)
    fmv.x.d t1, ft0
    li t2, 0x4004000000000000
    bne t1, t2, fail4

    li t0, 0x0123456789abcdef
    fmv.d.x ft1, t0
    fsd ft1, 16(s0)
    lwu t1, 16(s0)
    li t2, 0x89abcdef
    bne t1, t2, fail5
    lwu t1, 20(s0)
    li t2, 0x01234567
    bne t1, t2, fail5
    ld t1, 16(s0)
    li t2, 0x0123456789abcdef
    bne t1, t2, fail5

    # ---------------------------------------------------------------
    # 单精度算术
    # ---------------------------------------------------------------
    csrw fcsr, zero
    li t0, 0x3fc00000          # 1.5f
    fmv.w.x ft0, t0
    li t0, 0x40100000          # 2.25f
    fmv.w.x ft1, t0
    fadd.s ft2, ft0, ft1
    CHECK_S ft2, 0x40700000, 6       # 3.75f
    csrr t0, fcsr
    andi t0, t0, 0x1f
    bnez t0, fail6

    li t0, 0x40400000          # 3.0f
    fmv.w.x ft3, t0
    fsub.s ft2, ft0, ft3
    CHECK_S ft2, 0xbfc00000, 7       # -1.5f

    li t0, 0x40400000          # 3.0f
    fmv.w.x ft0, t0
    li t0, 0x40200000          # 2.5f
    fmv.w.x ft1, t0
    fmul.s ft2, ft0, ft1
    CHECK_S ft2, 0x40f00000, 8       # 7.5f

    csrw fcsr, zero
    li t0, 0x3f800000          # 1.0f
    fmv.w.x ft0, t0
    li t0, 0x40400000          # 3.0f
    fmv.w.x ft1, t0
    fdiv.s ft2, ft0, ft1
    CHECK_S ft2, 0x3eaaaaab, 9       # 1/3 最近舍入
    csrr t0, fcsr
    andi t0, t0, 0x1f
    li t1, 1                   # NX
    bne t0, t1, fail9

    csrw fcsr, zero
    li t0, 0x40000000          # 2.0f
    fmv.w.x ft0, t0
    fsqrt.s ft2, ft0
    CHECK_S ft2, 0x3fb504f3, 10
    csrr t0, fcsr
    andi t0, t0, 0x1f
    li t1, 1
    bne t0, t1, fail10

    # ---------------------------------------------------------------
    # 单精度 FMA
    # ---------------------------------------------------------------
    li t0, 0x40000000          # 2.0f
    fmv.w.x ft0, t0
    li t0, 0x40400000          # 3.0f
    fmv.w.x ft1, t0
    li t0, 0x3f800000          # 1.0f
    fmv.w.x ft3, t0

    fmadd.s ft2, ft0, ft1, ft3
    CHECK_S ft2, 0x40e00000, 11      # 7.0f
    fmsub.s ft2, ft0, ft1, ft3
    CHECK_S ft2, 0x40a00000, 12      # 5.0f
    fnmsub.s ft2, ft0, ft1, ft3
    CHECK_S ft2, 0xc0a00000, 13      # -5.0f
    fnmadd.s ft2, ft0, ft1, ft3
    CHECK_S ft2, 0xc0e00000, 14      # -7.0f

    # ---------------------------------------------------------------
    # 单精度 MIN/MAX 与符号注入
    # ---------------------------------------------------------------
    li t0, 0x80000000          # -0.0f
    fmv.w.x ft0, t0
    li t0, 0x00000000          # +0.0f
    fmv.w.x ft1, t0
    fmin.s ft2, ft0, ft1
    CHECK_S ft2, 0x80000000, 15
    fmax.s ft2, ft0, ft1
    CHECK_S ft2, 0x00000000, 15

    li t0, 0x7fc00000          # qNaN
    fmv.w.x ft0, t0
    li t0, 0x40400000          # 3.0f
    fmv.w.x ft1, t0
    csrw fcsr, zero
    fmin.s ft2, ft0, ft1
    CHECK_S ft2, 0x40400000, 16
    fmax.s ft2, ft1, ft0
    CHECK_S ft2, 0x40400000, 16
    fmin.s ft2, ft0, ft0
    CHECK_S ft2, 0x7fc00000, 16
    csrr t0, fcsr
    andi t0, t0, 0x1f
    bnez t0, fail16

    li t0, 0x3fc00000          # 1.5f
    fmv.w.x ft0, t0
    li t0, 0xc0000000          # -2.0f
    fmv.w.x ft1, t0
    fsgnj.s ft2, ft0, ft1
    CHECK_S ft2, 0xbfc00000, 17      # -1.5f
    fsgnjn.s ft2, ft0, ft1
    CHECK_S ft2, 0x3fc00000, 18      # +1.5f
    fsgnjx.s ft2, ft0, ft1
    CHECK_S ft2, 0xbfc00000, 19      # -1.5f

    # ---------------------------------------------------------------
    # 单精度比较
    # ---------------------------------------------------------------
    li t0, 0x3f800000          # 1.0f
    fmv.w.x ft0, t0
    li t0, 0x40000000          # 2.0f
    fmv.w.x ft1, t0
    feq.s a0, ft0, ft1
    bnez a0, fail20
    flt.s a0, ft0, ft1
    beqz a0, fail20
    fle.s a0, ft0, ft0
    beqz a0, fail20

    li t0, 0x80000000          # -0.0f
    fmv.w.x ft0, t0
    li t0, 0x00000000          # +0.0f
    fmv.w.x ft1, t0
    flt.s a0, ft0, ft1
    bnez a0, fail20
    fle.s a0, ft0, ft1
    beqz a0, fail20

    li t0, 0x7fc00000          # qNaN
    fmv.w.x ft0, t0
    li t0, 0x3f800000
    fmv.w.x ft1, t0
    feq.s a0, ft0, ft1
    bnez a0, fail21
    flt.s a0, ft0, ft1
    bnez a0, fail21
    fle.s a0, ft1, ft0
    bnez a0, fail21

    # sNaN 比较必须置 NV。
    li t0, 0x7f800001          # sNaN
    fmv.w.x ft0, t0
    csrw fcsr, zero
    flt.s a0, ft0, ft1
    bnez a0, fail22
    csrr t0, fcsr
    andi t0, t0, 0x1f
    li t1, 0x10
    bne t0, t1, fail22

    # ---------------------------------------------------------------
    # 单精度 FCVT / FMV
    # ---------------------------------------------------------------
    li t0, 0x40766666          # 3.7f
    fmv.w.x ft0, t0
    fcvt.w.s a0, ft0, rtz
    li t1, 3
    bne a0, t1, fail23
    fcvt.w.s a0, ft0, rne
    li t1, 4
    bne a0, t1, fail23

    li t0, 0xc0766666          # -3.7f
    fmv.w.x ft0, t0
    fcvt.w.s a0, ft0, rtz
    li t1, -3
    bne a0, t1, fail23
    fcvt.w.s a0, ft0, rdn
    li t1, -4
    bne a0, t1, fail23
    fcvt.w.s a0, ft0, rup
    li t1, -3
    bne a0, t1, fail23

    li t0, 0x40766666          # 3.7f
    fmv.w.x ft0, t0
    fcvt.wu.s a0, ft0, rtz
    li t1, 3
    bne a0, t1, fail24

    li t0, 0xbf800000          # -1.0f
    fmv.w.x ft0, t0
    csrw fcsr, zero
    fcvt.wu.s a0, ft0, rtz
    bnez a0, fail24
    csrr t0, fcsr
    andi t0, t0, 0x1f
    li t1, 0x10
    bne t0, t1, fail24

    li a0, -7
    fcvt.s.w ft0, a0
    CHECK_S ft0, 0xc0e00000, 25

    li t0, 0x80000000          # -0.0f，fmv.x.w 必须符号扩展
    fmv.w.x ft0, t0
    fmv.x.w a0, ft0
    li t1, 0xffffffff80000000
    bne a0, t1, fail26

    li t0, 0x01234567
    fmv.w.x ft0, t0
    fmv.x.w a0, ft0
    li t1, 0x01234567
    bne a0, t1, fail26

    # ---------------------------------------------------------------
    # 双精度算术
    # ---------------------------------------------------------------
    csrw fcsr, zero
    li t0, 0x3ff8000000000000  # 1.5
    fmv.d.x ft0, t0
    li t0, 0x4002000000000000  # 2.25
    fmv.d.x ft1, t0
    fadd.d ft2, ft0, ft1
    CHECK_D ft2, 0x400e000000000000, 27   # 3.75

    li t0, 0x4008000000000000  # 3.0
    fmv.d.x ft3, t0
    fsub.d ft2, ft0, ft3
    CHECK_D ft2, 0xbff8000000000000, 28   # -1.5

    li t0, 0x4008000000000000  # 3.0
    fmv.d.x ft0, t0
    li t0, 0x4004000000000000  # 2.5
    fmv.d.x ft1, t0
    fmul.d ft2, ft0, ft1
    CHECK_D ft2, 0x401e000000000000, 29   # 7.5

    csrw fcsr, zero
    li t0, 0x3ff0000000000000  # 1.0
    fmv.d.x ft0, t0
    li t0, 0x4008000000000000  # 3.0
    fmv.d.x ft1, t0
    fdiv.d ft2, ft0, ft1
    CHECK_D ft2, 0x3fd5555555555555, 30
    csrr t0, fcsr
    andi t0, t0, 0x1f
    li t1, 1
    bne t0, t1, fail30

    csrw fcsr, zero
    li t0, 0x4000000000000000  # 2.0
    fmv.d.x ft0, t0
    fsqrt.d ft2, ft0
    CHECK_D ft2, 0x3ff6a09e667f3bcd, 31
    csrr t0, fcsr
    andi t0, t0, 0x1f
    li t1, 1
    bne t0, t1, fail31

    # ---------------------------------------------------------------
    # 双精度 FMA
    # ---------------------------------------------------------------
    li t0, 0x4000000000000000  # 2.0
    fmv.d.x ft0, t0
    li t0, 0x4008000000000000  # 3.0
    fmv.d.x ft1, t0
    li t0, 0x3ff0000000000000  # 1.0
    fmv.d.x ft3, t0
    fmadd.d ft2, ft0, ft1, ft3
    CHECK_D ft2, 0x401c000000000000, 32   # 7
    fmsub.d ft2, ft0, ft1, ft3
    CHECK_D ft2, 0x4014000000000000, 33   # 5
    fnmsub.d ft2, ft0, ft1, ft3
    CHECK_D ft2, 0xc014000000000000, 34   # -5
    fnmadd.d ft2, ft0, ft1, ft3
    CHECK_D ft2, 0xc01c000000000000, 35   # -7

    # ---------------------------------------------------------------
    # 双精度 MIN/MAX 与符号注入
    # ---------------------------------------------------------------
    li t0, 0x8000000000000000  # -0.0
    fmv.d.x ft0, t0
    li t0, 0x0000000000000000  # +0.0
    fmv.d.x ft1, t0
    fmin.d ft2, ft0, ft1
    CHECK_D ft2, 0x8000000000000000, 36
    fmax.d ft2, ft0, ft1
    CHECK_D ft2, 0x0000000000000000, 36

    li t0, 0x7ff8000000000000  # qNaN
    fmv.d.x ft0, t0
    li t0, 0x4008000000000000  # 3.0
    fmv.d.x ft1, t0
    csrw fcsr, zero
    fmin.d ft2, ft0, ft1
    CHECK_D ft2, 0x4008000000000000, 37
    fmax.d ft2, ft1, ft0
    CHECK_D ft2, 0x4008000000000000, 37
    fmin.d ft2, ft0, ft0
    CHECK_D ft2, 0x7ff8000000000000, 37
    csrr t0, fcsr
    andi t0, t0, 0x1f
    bnez t0, fail37

    li t0, 0x3ff8000000000000  # 1.5
    fmv.d.x ft0, t0
    li t0, 0xc000000000000000  # -2.0
    fmv.d.x ft1, t0
    fsgnj.d ft2, ft0, ft1
    CHECK_D ft2, 0xbff8000000000000, 38
    fsgnjn.d ft2, ft0, ft1
    CHECK_D ft2, 0x3ff8000000000000, 39
    fsgnjx.d ft2, ft0, ft1
    CHECK_D ft2, 0xbff8000000000000, 40

    # ---------------------------------------------------------------
    # 双精度比较
    # ---------------------------------------------------------------
    li t0, 0x3ff0000000000000  # 1.0
    fmv.d.x ft0, t0
    li t0, 0x4000000000000000  # 2.0
    fmv.d.x ft1, t0
    feq.d a0, ft0, ft1
    bnez a0, fail41
    flt.d a0, ft0, ft1
    beqz a0, fail41
    fle.d a0, ft0, ft0
    beqz a0, fail41

    li t0, 0x7ff8000000000000  # qNaN
    fmv.d.x ft0, t0
    li t0, 0x3ff0000000000000
    fmv.d.x ft1, t0
    feq.d a0, ft0, ft1
    bnez a0, fail42
    flt.d a0, ft0, ft1
    bnez a0, fail42
    fle.d a0, ft1, ft0
    bnez a0, fail42

    li t0, 0x7ff0000000000001  # sNaN
    fmv.d.x ft0, t0
    csrw fcsr, zero
    flt.d a0, ft0, ft1
    bnez a0, fail43
    csrr t0, fcsr
    andi t0, t0, 0x1f
    li t1, 0x10
    bne t0, t1, fail43

    # ---------------------------------------------------------------
    # 双精度 FCVT / FMV
    # ---------------------------------------------------------------
    li t0, 0x400d99999999999a  # 3.7
    fmv.d.x ft0, t0
    fcvt.w.d a0, ft0, rtz
    li t1, 3
    bne a0, t1, fail44
    fcvt.w.d a0, ft0, rne
    li t1, 4
    bne a0, t1, fail44

    li t0, 0xbff0000000000000  # -1.0
    fmv.d.x ft0, t0
    csrw fcsr, zero
    fcvt.wu.d a0, ft0, rtz
    bnez a0, fail45
    csrr t0, fcsr
    andi t0, t0, 0x1f
    li t1, 0x10
    bne t0, t1, fail45

    li t0, 0x3ff8000000000000  # 1.5
    fmv.d.x ft0, t0
    fcvt.l.d a0, ft0, rtz
    li t1, 1
    bne a0, t1, fail46

    li t0, 0xbf80000000000000  # -1.0
    fmv.d.x ft0, t0
    csrw fcsr, zero
    fcvt.lu.d a0, ft0, rtz
    bnez a0, fail47
    csrr t0, fcsr
    andi t0, t0, 0x1f
    li t1, 0x10
    bne t0, t1, fail47

    li a0, -7
    fcvt.d.w ft0, a0
    CHECK_D ft0, 0xc01c000000000000, 48

    li a0, -1
    fcvt.d.wu ft0, a0          # 0xffffffff -> 4294967295.0
    CHECK_D ft0, 0x41efffffffe00000, 49

    # S <-> D
    li t0, 0x3fc00000          # 1.5f
    fmv.w.x ft0, t0
    fcvt.d.s ft1, ft0
    CHECK_D ft1, 0x3ff8000000000000, 50
    li t0, 0x3fd5555555555555  # 1/3 double
    fmv.d.x ft0, t0
    csrw fcsr, zero
    fcvt.s.d ft1, ft0
    CHECK_S ft1, 0x3eaaaaab, 51
    csrr t0, fcsr
    andi t0, t0, 0x1f
    li t1, 1                   # NX
    bne t0, t1, fail51

    # 溢出：1e300 -> +inf，OF|NX
    li t0, 0x7e37e43c8800759c
    fmv.d.x ft0, t0
    csrw fcsr, zero
    fcvt.s.d ft1, ft0
    CHECK_S ft1, 0x7f800000, 52
    csrr t0, fcsr
    andi t0, t0, 0x1f
    li t1, 5                   # OF|NX
    bne t0, t1, fail52

    # 下溢：1e-300 -> +0，UF|NX
    li t0, 0x01a56e1fc2f8f359
    fmv.d.x ft0, t0
    csrw fcsr, zero
    fcvt.s.d ft1, ft0
    CHECK_S ft1, 0x00000000, 53
    csrr t0, fcsr
    andi t0, t0, 0x1f
    li t1, 3                   # UF|NX
    bne t0, t1, fail53

    # FMV 64 位往返 / FLEN=64
    li t0, 0xdeadbeefcafebabe
    fmv.d.x ft0, t0
    fmv.x.d a0, ft0
    li t1, 0xdeadbeefcafebabe
    bne a0, t1, fail54

    # ---------------------------------------------------------------
    # FCLASS（覆盖 NaN/Inf/零/正规数）
    # ---------------------------------------------------------------
    li t0, 0x7ff0000000000000
    fmv.d.x ft0, t0
    fclass.d a0, ft0
    li t1, 0x080
    bne a0, t1, fail55
    li t0, 0xfff0000000000000
    fmv.d.x ft0, t0
    fclass.d a0, ft0
    li t1, 0x001
    bne a0, t1, fail55
    li t0, 0x0000000000000000
    fmv.d.x ft0, t0
    fclass.d a0, ft0
    li t1, 0x010
    bne a0, t1, fail55
    li t0, 0x8000000000000000
    fmv.d.x ft0, t0
    fclass.d a0, ft0
    li t1, 0x008
    bne a0, t1, fail55
    li t0, 0x7ff8000000000000
    fmv.d.x ft0, t0
    fclass.d a0, ft0
    li t1, 0x200
    bne a0, t1, fail55
    li t0, 0x7ff0000000000001
    fmv.d.x ft0, t0
    fclass.d a0, ft0
    li t1, 0x100
    bne a0, t1, fail55

    # ---------------------------------------------------------------
    # NaN 规范化与 NV
    # ---------------------------------------------------------------
    li t0, 0x7ff8000000000000  # qNaN
    fmv.d.x ft0, t0
    li t0, 0x3ff0000000000000  # 1.0
    fmv.d.x ft1, t0
    csrw fcsr, zero
    fadd.d ft2, ft0, ft1
    CHECK_D ft2, 0x7ff8000000000000, 56
    csrr t0, fcsr
    andi t0, t0, 0x1f
    bnez t0, fail56

    li t0, 0x7ff0000000000001  # sNaN
    fmv.d.x ft0, t0
    csrw fcsr, zero
    fadd.d ft2, ft0, ft1
    CHECK_D ft2, 0x7ff8000000000000, 57
    csrr t0, fcsr
    andi t0, t0, 0x1f
    li t1, 0x10
    bne t0, t1, fail57

    # ---------------------------------------------------------------
    # 舍入模式：1.0 + 2^-53 恰好位于两个 double 的中点
    # ---------------------------------------------------------------
    li t0, 0x3ff0000000000000  # 1.0
    fmv.d.x ft0, t0
    li t0, 0x3ca0000000000000  # 2^-53
    fmv.d.x ft1, t0

    csrw fcsr, zero
    fadd.d ft2, ft0, ft1, rne
    CHECK_D ft2, 0x3ff0000000000000, 58   # ties-to-even -> 1.0
    fadd.d ft2, ft0, ft1, rtz
    CHECK_D ft2, 0x3ff0000000000000, 59
    fadd.d ft2, ft0, ft1, rdn
    CHECK_D ft2, 0x3ff0000000000000, 60
    fadd.d ft2, ft0, ft1, rup
    CHECK_D ft2, 0x3ff0000000000001, 61
    fadd.d ft2, ft0, ft1, rmm
    CHECK_D ft2, 0x3ff0000000000001, 62

    # 负数方向：-1.0 + -2^-53
    li t0, 0xbff0000000000000
    fmv.d.x ft0, t0
    li t0, 0xbca0000000000000
    fmv.d.x ft1, t0
    fadd.d ft2, ft0, ft1, rne
    CHECK_D ft2, 0xbff0000000000000, 63
    fadd.d ft2, ft0, ft1, rdn
    CHECK_D ft2, 0xbff0000000000001, 64
    fadd.d ft2, ft0, ft1, rup
    CHECK_D ft2, 0xbff0000000000000, 65

    # 动态 frm：fsrm=RUP 后，无 rm 后缀的浮点指令使用 frm。
    li t0, 3
    fsrm t0
    frrm a0
    li t1, 3
    bne a0, t1, fail66
    li t0, 0x3ff0000000000000
    fmv.d.x ft0, t0
    li t0, 0x3ca0000000000000
    fmv.d.x ft1, t0
    fadd.d ft2, ft0, ft1       # 默认 dyn
    CHECK_D ft2, 0x3ff0000000000001, 66

    # ---------------------------------------------------------------
    # fcsr sticky flags / frflags / fsflags / fscsr
    # ---------------------------------------------------------------
    csrw fcsr, zero
    li t0, 0x3ff0000000000000
    fmv.d.x ft0, t0
    li t0, 0x4008000000000000
    fmv.d.x ft1, t0
    fdiv.d ft2, ft0, ft1       # NX
    csrr t0, fcsr
    andi t0, t0, 0x1f
    li t1, 1
    bne t0, t1, fail67
    fadd.d ft2, ft0, ft0       # 精确，不改变 sticky flags
    csrr t0, fcsr
    andi t0, t0, 0x1f
    bne t0, t1, fail67

    frflags a0
    li t1, 1
    bne a0, t1, fail67
    li t0, 0x10
    fsflags t0                 # OR 入 NV
    frflags a0
    li t1, 0x11
    bne a0, t1, fail67

    li t0, 0x24                # frm=RTZ(1<<5), flags=OF(4)
    fscsr t0
    frrm a0
    li t1, 1
    bne a0, t1, fail68
    frflags a0
    li t1, 4
    bne a0, t1, fail68

    li t0, 0x61                # frm=RUP(3<<5), flags=NX(1)
    fscsr t0
    csrr a0, fcsr
    li t1, 0x61
    bne a0, t1, fail68

    # ---------------------------------------------------------------
    # mstatus.FS 最终应为 Dirty(3)
    # ---------------------------------------------------------------
    csrr t0, mstatus
    li t1, 0x6000
    and t0, t0, t1
    li t1, 0x6000
    bne t0, t1, fail69

    li a0, 0
    j halt

# 失败编号：每个 failN 使用独立编号，便于定位第一处错误。
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
fail35: li a0, 35; j halt
fail36: li a0, 36; j halt
fail37: li a0, 37; j halt
fail38: li a0, 38; j halt
fail39: li a0, 39; j halt
fail40: li a0, 40; j halt
fail41: li a0, 41; j halt
fail42: li a0, 42; j halt
fail43: li a0, 43; j halt
fail44: li a0, 44; j halt
fail45: li a0, 45; j halt
fail46: li a0, 46; j halt
fail47: li a0, 47; j halt
fail48: li a0, 48; j halt
fail49: li a0, 49; j halt
fail50: li a0, 50; j halt
fail51: li a0, 51; j halt
fail52: li a0, 52; j halt
fail53: li a0, 53; j halt
fail54: li a0, 54; j halt
fail55: li a0, 55; j halt
fail56: li a0, 56; j halt
fail57: li a0, 57; j halt
fail58: li a0, 58; j halt
fail59: li a0, 59; j halt
fail60: li a0, 60; j halt
fail61: li a0, 61; j halt
fail62: li a0, 62; j halt
fail63: li a0, 63; j halt
fail64: li a0, 64; j halt
fail65: li a0, 65; j halt
fail66: li a0, 66; j halt
fail67: li a0, 67; j halt
fail68: li a0, 68; j halt
fail69: li a0, 69; j halt

halt:
    .word 0x0000000b

# 正向镜像不应触发任何异常；若触发，报告 cause/mepc 并失败。
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
