    # RV64 trap tval/stval DiffTest 回归。
    #
    # 不使用运行期 store 构造页表：页表作为镜像数据预置在 0x80004000，
    # 因而可以在直接 NPC 上验证 illegal / 非对齐 / 页错误 的 mtval/stval
    # 注入，而不会被无关的写通路问题干扰。
    .section .text
    .globl _start
_start:
    # ---- M 模式：illegal instruction 的 mtval ----
    la      t0, m_trap
    csrw    mtvec, t0
    li      s9, 0
    la      s0, illegal_site
illegal_site:
    .word   0xffffffff
    li      t0, 1
    bne     s9, t0, fail1
    li      t0, 2
    bne     s10, t0, fail2
    bne     s11, s0, fail3

    # ---- M 模式：非对齐 load 的 mtval ----
    li      s9, 0
    li      s0, 0x80005001
    lw      t1, 0(s0)
    li      t0, 1
    bne     s9, t0, fail4
    li      t0, 4
    bne     s10, t0, fail5
    bne     s11, s0, fail6

    # ---- 委托页错误到 S 模式 ----
    li      t0, 0x0000b000          # medeleg: cause 12/13/15
    csrw    medeleg, t0
    la      t0, s_trap
    csrw    stvec, t0

    li      t0, 0x8000000000080004  # Sv39, root PPN=0x80004
    csrw    satp, t0
    sfence.vma

    li      t0, 0x00000800          # mstatus.MPP=S
    csrw    mstatus, t0
    la      t0, s_entry
    csrw    mepc, t0
    mret

s_entry:
    # 1) 未映射页 load page fault，stval 必须是完整 64 位 VA。
    li      s9, 0
    li      s0, 0x40002000
    ld      t1, 0(s0)
    li      t0, 1
    bne     s9, t0, fail7
    li      t0, 13
    bne     s10, t0, fail8
    bne     s11, s0, fail9

    # 2) 只读页 store page fault。
    li      s9, 0
    li      s0, 0x40003000
    sd      zero, 0(s0)
    li      t0, 1
    bne     s9, t0, fail10
    li      t0, 15
    bne     s10, t0, fail11
    bne     s11, s0, fail12

    # 3) 非规范 Sv39 地址：stval 保留高 32 位。
    li      s9, 0
    li      s0, 0x0000008000000000
    ld      t1, 0(s0)
    li      t0, 1
    bne     s9, t0, fail13
    li      t0, 13
    bne     s10, t0, fail14
    bne     s11, s0, fail15

    # 4) 正常映射页仍可访问，且 tval 没有被上一次异常污染。
    li      s0, 0x40000000
    ld      t1, 0(s0)
    bnez    t1, fail16

    li      a0, 0
    .word   0x0000000b

# M 模式 trap：记录 cause/tval，跳过故障指令后返回。
m_trap:
    csrr    s10, mcause
    csrr    s11, mtval
    addi    s9, s9, 1
    csrr    t0, mepc
    addi    t0, t0, 4
    csrw    mepc, t0
    mret

# S 模式 trap：记录 cause/tval，跳过故障指令后返回。
s_trap:
    csrr    s10, scause
    csrr    s11, stval
    addi    s9, s9, 1
    csrr    t0, sepc
    addi    t0, t0, 4
    csrw    sepc, t0
    sret

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

    # 预置 Sv39 页表：root=0x80004000, L1=0x80005000, L2=0x80006000。
    # root[2] 为 1GiB identity 超级页，覆盖代码/页表自身。
    .section .pagetables,"aw",@progbits
    .balign 4096
    .quad   0
    .quad   0x20001401              # root[1] -> L1
    .quad   0x200000cf              # root[2] 1GiB identity, V R W X A D
    .zero   4096 - 3 * 8

    # L1[0] -> L2
    .quad   0x20001801
    .zero   4096 - 8

    # L2[0] -> PA 0x80007000 RW；L2[3] -> PA 0x80008000 read-only。
    .quad   0x20001ccf
    .zero   2 * 8
    .quad   0x20002043
    .zero   4096 - 4 * 8
