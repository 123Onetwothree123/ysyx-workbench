# RV64D + C.FP 正向裸机镜像（GCC -march=rv64gc -mabi=lp64d）。
#
# 重点验证 RV32/RV64 同编码位的差异：RV64 的 C.FLD/C.FSD 及 SP 形式
# 必须展开为浮点访存，而 0x6188/0xe588/0x6522/0xe82e 这些编码位在
# RV64 仍必须是 C.LD/C.SD/C.LDSP/C.SDSP，不能被误译成 C.FLW/C.FSW。
# 同时检查 64 位 FPR、fcsr/fflags、mstatus.FS 和 FLD 非对齐陷入。

    .section .data
    .align 3
buf:        .zero 512
scratch:    .zero 32
trap_count: .word 0
trap_cause: .word 0

    .section .text
    .globl _start

.macro CHECK reg, val, code
    li      t6, \val
    beq     \reg, t6, 9f
    li      a0, \code
    j       halt
9:
.endm

_start:
    la      t0, trap_entry
    csrw    mtvec, t0
    li      sp, 0x83fff000

    li      t0, 0x2000
    csrs    mstatus, t0
    csrw    fcsr, zero

    la      s0, buf
    la      s1, scratch

    # ---- C.FLD/C.FSD 往返（8 字节）----
    li      t0, 0x0123456789abcdef
    sd      t0, 0(s0)
    c.fld   f8, 0(s0)
    c.fsd   f8, 8(s0)
    ld      t1, 8(s0)
    bne     t0, t1, fail1

    # 最大偏移 248
    li      t0, 0xdeadbeef0badf00d
    sd      t0, 248(s0)
    c.fld   f15, 248(s0)
    c.fsd   f15, 16(s0)
    ld      t1, 16(s0)
    bne     t0, t1, fail2

    # ---- C.FLDSP/C.FSDSP ----
    li      t0, 0x1122334455667788
    sd      t0, 8(sp)
    c.fldsp f8, 8(sp)
    c.fsdsp f8, 16(sp)
    ld      t1, 16(sp)
    bne     t0, t1, fail3

    li      t0, 0xcafebabefeedface
    sd      t0, 504(sp)
    c.fldsp f31, 504(sp)
    c.fsdsp f31, 24(sp)
    ld      t1, 24(sp)
    bne     t0, t1, fail4

    # ---- RV64 同编码位必须是整数 C.LD/C.SD ----
    li      t0, 0x55aa55aa55aa55aa
    sd      t0, 32(s0)
    c.ld    a1, 32(s0)
    c.sd    a1, 40(s0)
    ld      t1, 40(s0)
    bne     t0, t1, fail5
    sd      t0, 32(sp)
    c.ldsp  a2, 32(sp)
    c.sdsp  a2, 48(sp)
    ld      t1, 48(sp)
    bne     t0, t1, fail6

    # ---- D 精度算术与 NaN-box 无关的完整 64 位结果 ----
    li      t0, 0x3ff0000000000000  # 1.0
    sd      t0, 0(s1)
    li      t0, 0x4000000000000000  # 2.0
    sd      t0, 8(s1)
    fld     f0, 0(s1)
    fld     f1, 8(s1)
    fadd.d  f2, f0, f1
    fsd     f2, 16(s1)
    ld      t1, 16(s1)
    li      t0, 0x4008000000000000  # 3.0
    bne     t0, t1, fail7

    csrw    fcsr, zero
    fdiv.d  f2, f0, f3              # +inf, DZ
    fsd     f2, 24(s1)
    ld      t1, 24(s1)
    li      t0, 0x7ff0000000000000
    bne     t0, t1, fail8
    csrr    t0, fflags
    andi    t0, t0, 0x1f
    li      t1, 0x08
    bne     t0, t1, fail9

    csrr    t0, mstatus
    li      t1, 0x6000
    and     t0, t0, t1
    li      t1, 0x6000
    bne     t0, t1, fail10

    # ---- FLD 非对齐陷入（cause=4）----
    li      t0, 0x6000
    csrc    mstatus, t0
    li      t0, 0x2000
    csrs    mstatus, t0
    la      s2, misaligned_fld
    la      t0, trap_count
    lw      s3, 0(t0)
    jr      s2
after_misaligned:
    la      t0, trap_count
    lw      t3, 0(t0)
    addi    t3, t3, -1
    bne     s3, t3, fail11
    la      t0, trap_cause
    lw      t0, 0(t0)
    li      t1, 4
    bne     t0, t1, fail12

    li      a0, 0
    j       halt

fail1:  li a0, 1
    j halt
fail2:  li a0, 2
    j halt
fail3:  li a0, 3
    j halt
fail4:  li a0, 4
    j halt
fail5:  li a0, 5
    j halt
fail6:  li a0, 6
    j halt
fail7:  li a0, 7
    j halt
fail8:  li a0, 8
    j halt
fail9:  li a0, 9
    j halt
fail10: li a0, 10
    j halt
fail11: li a0, 11
    j halt
fail12: li a0, 12
    j halt

misaligned_fld:
    fld     f8, 4(s0)
    j       after_misaligned

    .balign 4
trap_entry:
    csrr    t0, mcause
    la      t1, trap_cause
    sw      t0, 0(t1)
    la      t1, trap_count
    lw      t2, 0(t1)
    addi    t2, t2, 1
    sw      t2, 0(t1)
    li      t1, 4
    bne     t0, t1, fail_trap
    csrr    t0, mepc
    addi    t0, t0, 4
    csrw    mepc, t0
    mret
fail_trap:
    li      a0, 13
    j       halt

halt:
    .option norvc
    .word   0x0000000b
