# mstatus.FS 与浮点 trap 上下文测试（RV32F / RV64D 共用源文件）。
#
# 验证：
#   1. FS=0 时 FADD 与 FLW 都触发 illegal instruction(cause=2)；
#   2. mepc 精确指向故障指令，mret 后不重复执行；
#   3. trap 时 mstatus.MPIE/MPP 正确，FS 保持 Off；
#   4. 在 trap handler 中置 FS=3 后可以访问浮点寄存器与 fcsr，
#      且故障前的 f8/fcsr 内容未被破坏；
#   5. 返回后 FS 保持 Dirty(3)，后续浮点指令可继续执行。
#
# 该文件不包含正向语义测试；正向语义见 guest_rv32f.S / guest_rv64d.S。
.section .data
.align 3
trap_count:    .word 0
trap_cause:    .word 0
trap_site:     .word 0
fs_seen:       .word 0
#if __riscv_xlen == 64
f8_expected:   .dword 0x400a000000000000   # 3.25
#else
f8_expected:   .word 0x40500000            # 3.25f
#endif
fcsr_expected: .word 0x61                  # frm=RUP, NX

.section .text
.globl _start
_start:
    la t0, trap_entry
    csrw mtvec, t0

    # 先建立 FS=3 的正常上下文，写入 f8 和 fcsr。
    li t0, 0x6000
    csrs mstatus, t0
    li t0, 0x61
    csrw fcsr, t0
#if __riscv_xlen == 64
    li t0, 0x400a000000000000
    fmv.d.x fs0, t0
#else
    li t0, 0x40500000
    fmv.w.x fs0, t0
#endif

    # 打开 MIE，以便 trap 时验证 MPIE 保存。
    li t0, 0x8
    csrs mstatus, t0

    # 第一次：FS=0 时执行 FADD。
    li t0, 0x6000
    csrc mstatus, t0
    csrr t0, mstatus
    li t1, 0x6000
    and t0, t0, t1
    bnez t0, fail1              # FS 必须能被清成 Off

    la s1, site0
site0:
#if __riscv_xlen == 64
    fadd.d ft0, ft0, ft0
#else
    fadd.s ft0, ft0, ft0
#endif
    # handler 把 mepc 改成 site0+4 后返回这里。

    la t0, trap_count
    lw t1, 0(t0)
    li t2, 1
    bne t1, t2, fail2
    la t0, trap_cause
    lw t1, 0(t0)
    li t2, 2
    bne t1, t2, fail3
    la t0, trap_site
#if __riscv_xlen == 64
    lwu t1, 0(t0)
#else
    lw t1, 0(t0)
#endif
    bne t1, s1, fail4
    la t0, fs_seen
    lw t1, 0(t0)
    bnez t1, fail5              # trap 时 FS 必须仍为 Off

    # handler 已把 FS 设为 Dirty，返回后应保持。
    csrr t0, mstatus
    li t1, 0x6000
    and t0, t0, t1
    li t1, 0x6000
    bne t0, t1, fail6

    # MIE 应在 mret 后恢复为 1。
    csrr t0, mstatus
    andi t0, t0, 0x8
    beqz t0, fail7

    # 第二次：FS=0 时执行 FLW，验证浮点 load 也受 FS 门控。
    li t0, 0x6000
    csrc mstatus, t0
    la s0, scratch
    la s1, site1
site1:
    flw ft0, 0(s0)
    # handler 把 mepc 改成 site1+4 后返回这里。

    la t0, trap_count
    lw t1, 0(t0)
    li t2, 2
    bne t1, t2, fail8
    la t0, trap_cause
    lw t1, 0(t0)
    li t2, 2
    bne t1, t2, fail9
    la t0, trap_site
#if __riscv_xlen == 64
    lwu t1, 0(t0)
#else
    lw t1, 0(t0)
#endif
    bne t1, s1, fail10
    la t0, fs_seen
    lw t1, 0(t0)
    bnez t1, fail11

    # trap 返回后还能正常执行浮点运算。
    li t0, 0x6000
    csrs mstatus, t0
#if __riscv_xlen == 64
    li t0, 0x3ff8000000000000  # 1.5
    fmv.d.x ft0, t0
    li t0, 0x4004000000000000  # 2.5
    fmv.d.x ft1, t0
    fadd.d ft2, ft0, ft1
    fmv.x.d a0, ft2
    li t1, 0x4010000000000000  # 4.0
    bne a0, t1, fail12
#else
    li t0, 0x3fc00000          # 1.5f
    fmv.w.x ft0, t0
    li t0, 0x40200000          # 2.5f
    fmv.w.x ft1, t0
    fadd.s ft2, ft0, ft1
    fmv.x.w a0, ft2
#if __riscv_xlen == 64
    slli a0, a0, 32
    srli a0, a0, 32
#endif
    li t1, 0x40800000          # 4.0f
    bne a0, t1, fail12
#endif

    csrr t0, mstatus
    li t1, 0x6000
    and t0, t0, t1
    li t1, 0x6000
    bne t0, t1, fail13

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

halt:
    .word 0x0000000b

# trap handler：只允许 cause=2，并检查 trap 上下文。
.align 2
trap_entry:
    csrr t0, mcause
    la t1, trap_cause
    sw t0, 0(t1)
    li t2, 2
    bne t0, t2, trap_fail_cause

    csrr t0, mepc
    la t1, trap_site
    sw t0, 0(t1)

    la t1, trap_count
    lw t2, 0(t1)
    addi t2, t2, 1
    sw t2, 0(t1)
    li t3, 1
    beq t2, t3, trap_check_site0
    li t3, 2
    beq t2, t3, trap_check_site1
    li a0, 87
    j halt

trap_check_site0:
    la t3, site0
    bne t0, t3, trap_fail_site
    j trap_check_mstatus
trap_check_site1:
    la t3, site1
    bne t0, t3, trap_fail_site

trap_check_mstatus:
    # FS 在 trap 时必须仍为 Off；MPP=M，MPIE=1。
    csrr t0, mstatus
    li t1, 0x6000
    and t2, t0, t1
    la t1, fs_seen
    sw t2, 0(t1)
    bnez t2, trap_fail_fs

    li t1, 0x1800             # MPP
    and t2, t0, t1
    li t1, 0x1800
    bne t2, t1, trap_fail_mp
    andi t2, t0, 0x80         # MPIE
    beqz t2, trap_fail_mp

    # handler 中主动打开 FS，验证浮点上下文可用。
    li t0, 0x6000
    csrs mstatus, t0
    csrr t0, mstatus
    li t1, 0x6000
    and t0, t0, t1
    li t1, 0x6000
    bne t0, t1, trap_fail_fs_on

    # f8 必须保持 trap 前的值。
#if __riscv_xlen == 64
    fmv.x.d t0, fs0
    la t1, f8_expected
    ld t1, 0(t1)
    bne t0, t1, trap_fail_f8
#else
    fmv.x.w t0, fs0
#if __riscv_xlen == 64
    slli t0, t0, 32
    srli t0, t0, 32
#endif
    la t1, f8_expected
    lw t1, 0(t1)
    bne t0, t1, trap_fail_f8
#endif

    # fcsr 也必须保持 trap 前的值。
    csrr t0, fcsr
    la t1, fcsr_expected
    lw t1, 0(t1)
    bne t0, t1, trap_fail_fcsr

    # 在 handler 内执行一条精确浮点运算，证明 trap 上下文可继续使用 FPU。
#if __riscv_xlen == 64
    li t0, 0x3ff8000000000000
    fmv.d.x ft0, t0
    li t0, 0x4004000000000000
    fmv.d.x ft1, t0
    fadd.d ft2, ft0, ft1
    fmv.x.d t0, ft2
    li t1, 0x4010000000000000
    bne t0, t1, trap_fail_handler_fp
#else
    li t0, 0x3fc00000
    fmv.w.x ft0, t0
    li t0, 0x40200000
    fmv.w.x ft1, t0
    fadd.s ft2, ft0, ft1
    fmv.x.w t0, ft2
#if __riscv_xlen == 64
    slli t0, t0, 32
    srli t0, t0, 32
#endif
    li t1, 0x40800000
    bne t0, t1, trap_fail_handler_fp
#endif

    # 跳过故障指令。两条故障指令都是 4 字节。
    csrr t0, mepc
    addi t0, t0, 4
    csrw mepc, t0
    mret

trap_fail_cause:      li a0, 80; j halt
trap_fail_site:       li a0, 81; j halt
trap_fail_fs:         li a0, 82; j halt
trap_fail_mp:         li a0, 83; j halt
trap_fail_f8:         li a0, 84; j halt
trap_fail_fcsr:       li a0, 85; j halt
trap_fail_handler_fp: li a0, 86; j halt
trap_fail_fs_on:      li a0, 88; j halt

.section .data
.align 3
scratch: .zero 8
