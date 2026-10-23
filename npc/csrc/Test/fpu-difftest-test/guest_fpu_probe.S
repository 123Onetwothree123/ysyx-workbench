# F/D 支持探针。
#
# 该镜像不依赖 NEMU REF，先在 DUT 上确认：
#   * F：fcsr 可访问、fmv/fadd/fsw/flw 正常工作；
#   * D（仅 RV64）：fmv.d.x/fadd.d/fsd/fld 正常工作；
#   * mstatus.FS 可由软件置位，并在执行浮点指令后变成 Dirty(3)。
#
# 结果约定的 a0：
#   0   F（RV32）/ F+D（RV64）全部可用；
#   21  RISCV_F=n：F 指令在首个浮点站点触发 illegal instruction；
#   22  RISCV_F=y、RISCV_D=n：F 可用，D 指令触发 illegal instruction；
#   23  F/D 能执行，但 mstatus.FS 没有变成 Dirty，属于部分实现；
#   25  出现 D 可用而 F 不可用的非法配置；
#   30/31 F/D 指令没有陷入但计算结果错误；
#   40  浮点站点产生了非 illegal-instruction 的异常。
#
# 只有 21/22 会被测试脚本解释为“扩展未启用”。计算错误永远不会被当成
# SKIP，避免用软浮点或空操作伪造 PASS。
.section .data
.align 3
phase:      .word 0
f_ok:       .word 0
d_ok:       .word 0
scratch:    .zero 16

.section .text
.globl _start
_start:
    la t0, trap_entry
    csrw mtvec, t0

    la t0, phase
    sw zero, 0(t0)
    la t0, f_ok
    sw zero, 0(t0)
    la t0, d_ok
    sw zero, 0(t0)

    # 置 mstatus.FS=Initial(1)；随后任何浮点指令都应把它变成 Dirty(3)。
    li t0, 0x2000
    csrs mstatus, t0

    # ---------------- F 阶段 ----------------
    la t0, phase
    li t1, 1
    sw t1, 0(t0)

    csrw fcsr, zero
    li t0, 0x3fc00000          # 1.5f
    fmv.w.x ft0, t0
    li t0, 0x40000000          # 2.0f
    fmv.w.x ft1, t0
    fadd.s ft2, ft0, ft1
    fmv.x.w t1, ft2
#if __riscv_xlen == 64
    slli t1, t1, 32
    srli t1, t1, 32
#endif
    li t2, 0x40600000          # 3.5f
    bne t1, t2, probe_f_value_bad

    # FLW/FSW：确认浮点访存通路也接通，而不是只实现寄存器运算。
    la t0, scratch
    fsw ft2, 0(t0)
    flw ft3, 0(t0)
    fmv.x.w t1, ft3
#if __riscv_xlen == 64
    slli t1, t1, 32
    srli t1, t1, 32
#endif
    li t2, 0x40600000
    bne t1, t2, probe_f_value_bad

    la t0, f_ok
    li t1, 1
    sw t1, 0(t0)

    csrr t0, mstatus
    li t1, 0x6000
    and t0, t0, t1
    li t1, 0x6000
    bne t0, t1, probe_fs_bad

after_f:
#if __riscv_xlen == 64
    # ---------------- D 阶段 ----------------
    la t0, phase
    li t1, 2
    sw t1, 0(t0)

    csrw fcsr, zero
    li t0, 0x3ff8000000000000  # 1.5
    fmv.d.x ft0, t0
    li t0, 0x4002000000000000  # 2.25
    fmv.d.x ft1, t0
    fadd.d ft2, ft0, ft1
    fmv.x.d t1, ft2
    li t2, 0x400e000000000000  # 3.75
    bne t1, t2, probe_d_value_bad

    la t0, scratch
    fsd ft2, 0(t0)
    fld ft3, 0(t0)
    fmv.x.d t1, ft3
    li t2, 0x400e000000000000
    bne t1, t2, probe_d_value_bad

    la t0, d_ok
    li t1, 1
    sw t1, 0(t0)

    csrr t0, mstatus
    li t1, 0x6000
    and t0, t0, t1
    li t1, 0x6000
    bne t0, t1, probe_fs_bad

after_d:
#else
after_d:
#endif

finish:
    la t0, f_ok
    lw s0, 0(t0)
    la t0, d_ok
    lw s1, 0(t0)

#if __riscv_xlen == 32
    bnez s0, probe_good
    li a0, 21
    j halt
#else
    beqz s0, finish_no_f
    beqz s1, finish_no_d
    j probe_good

finish_no_f:
    # F=n 时 D 也必须 illegal。若 D 反而能执行，说明配置/译码不一致。
    beqz s1, finish_no_f_no_d
    li a0, 25
    j halt

finish_no_f_no_d:
    li a0, 21
    j halt

finish_no_d:
    li a0, 22
    j halt
#endif

probe_good:
    li a0, 0
    j halt

probe_f_value_bad:
    li a0, 30
    j halt

probe_d_value_bad:
    li a0, 31
    j halt

probe_fs_bad:
    li a0, 23
    j halt

# 浮点站点只允许 illegal instruction(cause=2)。handler 直接把 mepc
# 改到阶段出口，避免在缺少 F/D 的核上反复执行同一条非法指令。
.align 2
trap_entry:
    csrr t0, mcause
    li t1, 2
    bne t0, t1, probe_trap_bad

    la t0, phase
    lw t1, 0(t0)
    li t2, 1
    beq t1, t2, probe_trap_f
    li t2, 2
    beq t1, t2, probe_trap_d
    j probe_trap_bad

probe_trap_f:
    la t0, f_ok
    sw zero, 0(t0)
    la t0, after_f
    csrw mepc, t0
    mret

probe_trap_d:
    la t0, d_ok
    sw zero, 0(t0)
    la t0, after_d
    csrw mepc, t0
    mret

probe_trap_bad:
    li a0, 40

halt:
    # csrc/Test 统一的仿真 halt：custom-0 编码 0x0000000b。
    .word 0x0000000b
