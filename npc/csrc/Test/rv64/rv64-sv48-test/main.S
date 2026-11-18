    # RV64 Sv48 独立裸机回归：satp.MODE=9、四级页表、1GiB 超级页取指，
    # 以及 VA 0x80_0000_0000 上的 4KiB 映射（VPN[3..0] 全部参与 walk）。
    # 与 Sv39 测试分开，避免把三级 walk 的通过误当成四级 walk。
    .section .text
    .globl _start
_start:
    li      sp, 0x8000b000

    # 页表页：root=0x80004000, L1=0x80005000, L2=0x80006000, L3=0x80007000。
    # 数据页：0x80008000。先清零整个页表区域，避免上电残留 PTE。
    li      t0, 0x80004000
    li      t1, 0x80008000
1:
    sd      zero, 0(t0)
    addi    t0, t0, 8
    bltu    t0, t1, 1b

    # identity 映射代码/页表：VA 0x80000000 的 VPN[3]=0、VPN[2]=2，
    # 所以 root[0] 指向 L1，L1[2] 作 1GiB 叶映射。root 是 Sv48 第 4 级，
    # 不能把 1GiB 叶直接放在 root 项里。
    li      t0, 0x20001401          # (PPN 0x80005 << 10) | V
    li      t1, 0x80004000
    sd      t0, 0(t1)               # root[0] -> L1
    sd      t0, 8(t1)               # root[1] -> L1，供 4KiB 映射复用
    li      t2, 0x80005000
    li      t0, 0x200000cf          # (PPN 0x80000 << 10) | V R W X A D
    sd      t0, 16(t2)              # L1[2] = 1GiB identity leaf

    # L1[0] -> L2, L2[0] -> L3；4KiB 映射 VA 0x80_0000_0000
    # （VPN[3]=1, VPN[2..0]=0）-> PA 0x80008000。
    li      t0, 0x20001801          # (PPN 0x80006 << 10) | V
    sd      t0, 0(t2)
    li      t3, 0x80006000
    li      t0, 0x20001c01          # (PPN 0x80007 << 10) | V
    sd      t0, 0(t3)
    li      t4, 0x80007000
    li      t0, 0x200020cf          # (PPN 0x80008 << 10) | V R W X A D
    sd      t0, 0(t4)               # L3[0]

    # 预先通过 1GiB identity 映射准备数据页内容。
    li      t0, 0x1122334455667788
    li      t5, 0x80008000
    sd      t0, 0(t5)

    # S 模式页错误委托 + stvec。
    li      t0, 0x0000b000          # medeleg: cause 12/13/15
    csrw    medeleg, t0
    la      t0, s_trap
    csrw    stvec, t0

    # satp.MODE=9(Sv48), ASID=0, PPN=0x80004。
    li      t0, 0x9000000000080004
    csrw    satp, t0
    sfence.vma

    # mret 进入 S，mstatus.MPP=S。
    li      t0, 0x00000800
    csrw    mstatus, t0
    la      t0, s_entry
    csrw    mepc, t0
    mret

s_entry:
    # 1) satp 必须完整保留 MODE=9（WARL 不得收敛成 Bare/Sv39）。
    csrr    t0, satp
    srli    t0, t0, 60
    li      t1, 9
    bne     t0, t1, fail1

    # 2) 四级 walk + 4KiB 映射：读、写、读，并检查物理别名。
    li      s9, 0
    li      t0, 0x8000000000
    ld      t1, 0(t0)
    li      t2, 0x1122334455667788
    bne     t1, t2, fail2
    li      t2, 0xaabbccddeeff0011
    sd      t2, 0(t0)
    ld      t1, 0(t0)
    bne     t1, t2, fail3
    li      t3, 0x80008000
    ld      t4, 0(t3)
    bne     t2, t4, fail4

    # 3) 未映射的 4KiB 页（L3[2]=0）必须 load page fault。
    li      s9, 0
    li      t0, 0x8000002000
    ld      t1, 0(t0)
    li      t0, 1
    bne     s9, t0, fail5
    li      t0, 13
    bne     s10, t0, fail6
    li      t0, 0x8000002000
    bne     s11, t0, fail7

    # 4) Sv48 非规范虚拟地址：bit63:48 必须是 bit47 的符号扩展。
    #    0x0001000000000000 的 bit48=1，但 bit47=0。
    li      s9, 0
    li      t0, 0x0001000000000000
    ld      t1, 0(t0)
    li      t0, 1
    bne     s9, t0, fail8
    li      t0, 13
    bne     s10, t0, fail9
    li      t0, 0x0001000000000000
    bne     s11, t0, fail10

    # 5) 再次访问 4KiB 页，验证 TLB 命中路径仍返回同一物理页。
    li      t0, 0x8000000000
    ld      t1, 0(t0)
    li      t2, 0xaabbccddeeff0011
    bne     t1, t2, fail11

    li      a0, 0
    .word   0x0000000b

# S 模式页错误处理：记录 scause/stval/次数，跳过故障指令后返回。
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
