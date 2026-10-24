# RV32D + C.FP 正向裸机镜像（GCC -march=rv32gc -mabi=ilp32d）。
#
# 覆盖：
#   - 32 位 AXI 数据通道上的 FLD/FSD 8 字节访存；
#   - FPR 的 64 位数据、FLW 的 NaN-box；
#   - fcsr/fflags 与 mstatus.FS；
#   - C.FLD/C.FSD/C.FLW/C.FSW 及 C.FLDSP/C.FSDSP/C.FLWSP/C.FSWSP
#     的 RV32 立即数布局和 SP 形式；
#   - FS=Off 时压缩/普通浮点指令都必须 illegal instruction；
#   - FLD 非对齐访问必须按 load address misaligned 陷入。
#
# 每个失败编号对应唯一致命路径；a0=0 + custom halt 是 GOOD TRAP。

    .section .data
    .align 3
buf:        .zero 512
scratch:    .zero 16
trap_count: .word 0
trap_cause: .word 0
trap_epc:   .word 0

    .section .text
    .globl _start

.macro CHECK reg, val, code
    li      t6, \val
    beq     \reg, t6, 9f
    li      a0, \code
    j       halt
9:
.endm

.macro CHECK_WORD addr, val, code
    lw      t6, \addr
    li      t5, \val
    beq     t6, t5, 9f
    li      a0, \code
    j       halt
9:
.endm

_start:
    la      t0, trap_entry
    csrw    mtvec, t0
    li      sp, 0x83fff000

    # mstatus.FS=Initial(1)，随后浮点指令应把它置为 Dirty(3)。
    li      t0, 0x2000
    csrs    mstatus, t0
    csrr    t0, mstatus
    li      t1, 0x6000
    and     t0, t0, t1
    li      t1, 0x2000
    bne     t0, t1, fail1

    csrw    fcsr, zero
    csrr    t0, fcsr
    bnez    t0, fail2

    la      s0, buf
    la      s1, scratch

    # ---- C.FLD / C.FSD：8 字节往返 ----
    li      t0, 0x01234567
    sw      t0, 0(s0)
    li      t0, 0x89abcdef
    sw      t0, 4(s0)
    c.fld   f8, 0(s0)
    c.fsd   f8, 8(s0)
    CHECK_WORD 8(s0), 0x01234567, 3
    CHECK_WORD 12(s0), 0x89abcdef, 4

    # ---- C.FLD/C.FSD 最大偏移 248：覆盖 offset[7:6|5|4:3] ----
    li      t0, 0xdeadbeef
    sw      t0, 248(s0)
    li      t0, 0x0badf00d
    sw      t0, 252(s0)
    c.fld   f15, 248(s0)
    c.fsd   f15, 16(s0)
    CHECK_WORD 16(s0), 0xdeadbeef, 5
    CHECK_WORD 20(s0), 0x0badf00d, 6

    # ---- C.FLW/C.FSW：32 位访存与不覆盖高字的 store ----
    li      t0, 0x3fc00000          # 1.5f
    sw      t0, 24(s0)
    li      t0, 0xa5a5a5a5
    sw      t0, 28(s0)
    sw      t0, 36(s0)              # C.FSW 不得覆盖相邻高字
    c.flw   f10, 24(s0)
    c.fsw   f10, 32(s0)
    CHECK_WORD 32(s0), 0x3fc00000, 7
    CHECK_WORD 36(s0), 0xa5a5a5a5, 8

    # C.FLW 必须把高 32 位 NaN-box 成全 1；用 C.FSD 观察完整 f 寄存器。
    c.flw   f11, 24(s0)
    c.fsd   f11, 40(s0)
    CHECK_WORD 40(s0), 0x3fc00000, 9
    CHECK_WORD 44(s0), 0xffffffff, 10

    # ---- C.FLDSP/C.FSDSP：SP 相对寻址（8 字节对齐）----
    li      t0, 0x11223344
    sw      t0, 8(sp)
    li      t0, 0x55667788
    sw      t0, 12(sp)
    c.fldsp f8, 8(sp)
    c.fsdsp f8, 16(sp)
    lw      t0, 16(sp)
    li      t1, 0x11223344
    bne     t0, t1, fail11
    lw      t0, 20(sp)
    li      t1, 0x55667788
    bne     t0, t1, fail12

    # 最大 SP 偏移 504，覆盖 uimm[8:6] 与 uimm[5]。
    li      t0, 0xcafebabe
    sw      t0, 504(sp)
    li      t0, 0xfeedface
    sw      t0, 508(sp)
    c.fldsp f31, 504(sp)
    c.fsdsp f31, 24(sp)
    lw      t0, 24(sp)
    li      t1, 0xcafebabe
    bne     t0, t1, fail13
    lw      t0, 28(sp)
    li      t1, 0xfeedface
    bne     t0, t1, fail14

    # ---- C.FLWSP/C.FSWSP：SP 相对 32 位访存 ----
    li      t0, 0x40000000          # 2.0f
    sw      t0, 4(sp)
    c.flwsp f10, 4(sp)
    c.fswsp f10, 12(sp)
    lw      t0, 12(sp)
    li      t1, 0x40000000
    bne     t0, t1, fail15

    li      t0, 0x7f7fffff          # FLT_MAX
    sw      t0, 252(sp)
    c.flwsp f31, 252(sp)
    c.fswsp f31, 32(sp)
    lw      t0, 32(sp)
    li      t1, 0x7f7fffff
    bne     t0, t1, fail16

    # C.FLWSP 也必须 NaN-box。
    c.fsdsp f31, 40(sp)
    lw      t0, 40(sp)
    li      t1, 0x7f7fffff
    bne     t0, t1, fail17
    lw      t0, 44(sp)
    li      t1, 0xffffffff
    bne     t0, t1, fail18

    # ---- D 精度算术仍走完整 64 位 FLEN ----
    la      t0, scratch
    li      t1, 0x00000000          # 1.0
    sw      t1, 0(t0)
    li      t1, 0x3ff00000
    sw      t1, 4(t0)
    li      t1, 0x00000000          # 2.0
    sw      t1, 8(t0)
    li      t1, 0x40000000
    sw      t1, 12(t0)
    fld     f0, 0(t0)
    fld     f1, 8(t0)
    fadd.d  f2, f0, f1              # 3.0
    fsd     f2, 0(s1)
    lw      t0, 0(s1)
    bnez    t0, fail19
    lw      t0, 4(s1)
    li      t1, 0x40080000
    bne     t0, t1, fail20

    # fcsr/fflags：1.0 / 0.0 = +inf，DZ 置位。
    csrw    fcsr, zero
    fdiv.d  f2, f0, f3              # f3 复位后为 +0.0
    fsd     f2, 0(s1)
    lw      t0, 4(s1)
    li      t1, 0x7ff00000
    bne     t0, t1, fail21
    csrr    t0, fflags
    andi    t0, t0, 0x1f
    li      t1, 0x08
    bne     t0, t1, fail22
    csrw    fcsr, zero
    csrr    t0, fcsr
    bnez    t0, fail23

    # 任意 F/D 指令退休后 mstatus.FS 必须是 Dirty。
    csrr    t0, mstatus
    li      t1, 0x6000
    and     t0, t0, t1
    li      t1, 0x6000
    bne     t0, t1, fail24

    # ---- FS=Off：C.FLD 和 FLD 都必须 illegal instruction ----
    li      t0, 0x6000
    csrc    mstatus, t0
    la      s2, fs_off_c_fld
    la      t0, trap_count
    lw      s3, 0(t0)
    jr      s2
after_fs_off_c_fld:
    la      t0, trap_count
    lw      t3, 0(t0)
    addi    t3, t3, -1
    bne     s3, t3, fail25
    la      t0, trap_cause
    lw      t0, 0(t0)
    li      t1, 2
    bne     t0, t1, fail26

    li      t0, 0x6000
    csrc    mstatus, t0
    la      s2, fs_off_fld
    la      t0, trap_count
    lw      s3, 0(t0)
    jr      s2
after_fs_off_fld:
    la      t0, trap_count
    lw      t3, 0(t0)
    addi    t3, t3, -1
    bne     s3, t3, fail27

    # 恢复 FS 供后续测试。
    li      t0, 0x2000
    csrs    mstatus, t0

    # ---- FLD 非对齐：load address misaligned(cause=4) ----
    la      s2, misaligned_fld
    la      t0, trap_count
    lw      s3, 0(t0)
    jr      s2
after_misaligned_fld:
    la      t0, trap_count
    lw      t3, 0(t0)
    addi    t3, t3, -1
    bne     s3, t3, fail28
    la      t0, trap_cause
    lw      t0, 0(t0)
    li      t1, 4
    bne     t0, t1, fail29

    li      a0, 0
    j       halt

fail1:  li a0, 1
    j halt
fail2:  li a0, 2
    j halt
fail11: li a0, 11
    j halt
fail12: li a0, 12
    j halt
fail13: li a0, 13
    j halt
fail14: li a0, 14
    j halt
fail15: li a0, 15
    j halt
fail16: li a0, 16
    j halt
fail17: li a0, 17
    j halt
fail18: li a0, 18
    j halt
fail19: li a0, 19
    j halt
fail20: li a0, 20
    j halt
fail21: li a0, 21
    j halt
fail22: li a0, 22
    j halt
fail23: li a0, 23
    j halt
fail24: li a0, 24
    j halt
fail25: li a0, 25
    j halt
fail26: li a0, 26
    j halt
fail27: li a0, 27
    j halt
fail28: li a0, 28
    j halt
fail29: li a0, 29
    j halt

fs_off_c_fld:
    c.fld   f8, 0(s0)
    j       after_fs_off_c_fld
fs_off_fld:
    fld     f8, 0(s0)
    j       after_fs_off_fld
misaligned_fld:
    fld     f8, 4(s0)
    j       after_misaligned_fld

# M 模式 trap handler：只接受 illegal instruction 或 load misaligned；
# 根据指令长度精确跳过故障指令（2 字节压缩 / 4 字节普通）。
    .balign 4
trap_entry:
    csrr    t0, mcause
    la      t1, trap_cause
    sw      t0, 0(t1)
    csrr    t0, mepc
    la      t1, trap_epc
    sw      t0, 0(t1)
    la      t1, trap_count
    lw      t2, 0(t1)
    addi    t2, t2, 1
    sw      t2, 0(t1)

    csrr    t0, mcause
    li      t1, 2
    beq     t0, t1, trap_skip
    li      t1, 4
    beq     t0, t1, trap_skip
    li      a0, 30
    j       halt

trap_skip:
    csrr    t0, mepc
    lhu     t1, 0(t0)
    andi    t1, t1, 3
    li      t2, 3
    beq     t1, t2, trap_skip32
    addi    t0, t0, 2
    j       trap_return
trap_skip32:
    addi    t0, t0, 4
trap_return:
    csrw    mepc, t0
    mret

halt:
    .option norvc
    .word   0x0000000b
