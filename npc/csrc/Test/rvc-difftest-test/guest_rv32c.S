# RV32C DiffTest 裸机镜像：16/32 位混合、C.ADDI/C.LW/C.SW/C.J/C.JALR/
# C.BEQZ/C.BNEZ、跨 cache line/页取指、ecall trap。
#
# 代码固定在 0x80000000。主流程必须位于 0x800003fe 之前：
#   - 0x800003fe 处的 32 位 addi 横跨 16/32/64B cache line 边界；
#   - 0x80001ffe 处的 32 位 addi 横跨 4KiB 页边界。
# RV32C 没有 C.LD/C.SD，8 字节跨边界用例由 RV64 镜像覆盖。
#
# 退出约定：a0==0 + custom-0 halt(0x0000000b) 为 GOOD TRAP；
# 失败时 a0=失败编号 + custom-0 halt，NPC 打印 BAD TRAP 并返回非 0。

    .section .text
    .globl _start
_start:
    la      t0, trap_handler
    csrw    mtvec, t0
    li      s9, 0                   # trap 计数

    # ---- C.ADDI：正负立即数 ----
    li      s2, 10
    c.addi  s2, 5
    li      t0, 15
    bne     s2, t0, fail1
    c.addi  s2, -3
    li      t0, 12
    bne     s2, t0, fail2

    # ---- C.SW/C.LW：相邻 cache line（0x...403c 与 0x...4040）----
    li      s0, 0x8000403c
    li      s1, 0x11223344
    c.sw    s1, 0(s0)
    li      s1, 0x55667788
    c.sw    s1, 4(s0)               # 0x80004040，下一条 line
    c.lw    a1, 0(s0)
    li      t0, 0x11223344
    bne     a1, t0, fail3
    c.lw    a1, 4(s0)
    li      t0, 0x55667788
    bne     a1, t0, fail4

    # ---- C.SW/C.LW：跨页（0x...5ffc 与 0x...6000）----
    li      s0, 0x80005ffc
    li      s1, 0x0badf00d
    c.sw    s1, 0(s0)
    li      s1, 0x0ddba11a
    c.sw    s1, 4(s0)               # 0x80006000，下一页
    c.lw    a1, 0(s0)
    li      t0, 0x0badf00d
    bne     a1, t0, fail5
    c.lw    a1, 4(s0)
    li      t0, 0x0ddba11a
    bne     a1, t0, fail6

    # ---- C.J：跳过故意设置的失败路径 ----
    c.j     cj_ok
    j       fail7
cj_ok:

    # ---- C.JALR + C.JR：ra 返回地址按 2B 对齐 ----
    la      t0, cjalr_target
    c.jalr  t0
cjalr_ret:
    j       cjalr_done
cjalr_target:
    li      s5, 0x5a5a
    c.jr    ra
cjalr_done:
    li      t0, 0x5a5a
    bne     s5, t0, fail8

    # ---- C.BEQZ / C.BNEZ ----
    li      s1, 0
    c.beqz  s1, cbeqz_ok
    j       fail9
cbeqz_ok:
    li      s1, 1
    c.bnez  s1, cbnez_ok
    j       fail10
cbnez_ok:

    # ---- 32 位指令横跨 16/32/64B cache line：0x800003fe ----
    li      t0, 0x800003fe
    c.jalr  t0
cross_line_back:
    li      t0, 1
    bne     s6, t0, fail11

    # ---- 32 位指令横跨 4KiB 页：0x80001ffe ----
    li      t0, 0x80001ffe
    c.jalr  t0
cross_page_back:
    li      t0, 1
    bne     s7, t0, fail12

    # ---- ecall trap：mcause=11，mepc 指向 ecall，handler 已 +4 ----
ecall_site:
    ecall
after_ecall:
    li      t0, 1
    bne     s9, t0, fail13
    li      t0, 11
    bne     s10, t0, fail14
    la      t0, ecall_site
    bne     s11, t0, fail15

    li      a0, 0
    .option norvc
    .word   0x0000000b              # custom-0 仿真 halt（不是架构 EBREAK）

# M 模式 trap handler：记录 cause/mepc，跳过 4B ecall 后 mret。
    .balign 4
trap_handler:
    csrr    s10, mcause
    csrr    s11, mepc
    addi    s9, s9, 1
    csrr    t0, mepc
    addi    t0, t0, 4
    csrw    mepc, t0
    mret


    # 32 位 addi 的字节地址是 0x800003fe，跨 16/32/64B cache line。
    .option norvc
    .org    0x03fe
    .globl cross_line_instr
cross_line_instr:
    addi    s6, s6, 1
    j       cross_line_back

    # 32 位 addi 的字节地址是 0x80001ffe，跨 4KiB 页。
    .org    0x1ffe
    .globl cross_page_instr
cross_page_instr:
    addi    s7, s7, 1
    j       cross_page_back

#define FAIL(n) \
    li a0, n;   \
    .option norvc; \
    .word 0x0000000b
fail1:  FAIL(1)
fail2:  FAIL(2)
fail3:  FAIL(3)
fail4:  FAIL(4)
fail5:  FAIL(5)
fail6:  FAIL(6)
fail7:  FAIL(7)
fail8:  FAIL(8)
fail9:  FAIL(9)
fail10: FAIL(10)
fail11: FAIL(11)
fail12: FAIL(12)
fail13: FAIL(13)
fail14: FAIL(14)
fail15: FAIL(15)
