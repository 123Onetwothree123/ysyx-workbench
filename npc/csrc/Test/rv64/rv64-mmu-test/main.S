    .section .text
    .globl _start
_start:
    li      sp, 0x80008000

    # 页表页：root=0x80004000, level1=0x80005000, level2=0x80006000。
    # 先整体清零，避免上电残留数据影响 walk。
    li      t0, 0x80004000
    li      t1, 0x80007000
1:
    sd      zero, 0(t0)
    addi    t0, t0, 8
    bltu    t0, t1, 1b

    # root[2] = 1GiB identity 超级页：VA/PA 0x80000000 起，覆盖代码和页表。
    li      t0, 0x200000cf          # (PPN 0x80000 << 10) | V R W X A D
    li      t1, 0x80004000
    sd      t0, 16(t1)              # VPN[2]=2

    # root[1] -> L1, L1[0] -> L2。4KiB 页映射从 VA 0x40000000 开始。
    li      t0, 0x20001401          # (PPN 0x80005 << 10) | V
    sd      t0, 8(t1)
    li      t2, 0x80005000
    li      t0, 0x20001801          # (PPN 0x80006 << 10) | V
    sd      t0, 0(t2)

    li      t3, 0x80006000
    # L2[0] -> PA 0x80007000，可读写。
    li      t0, 0x20001ccf
    sd      t0, 0(t3)
    # L2[3] -> PA 0x80008000，只读（V R A），store 必须页错误。
    li      t0, 0x20002043
    sd      t0, 24(t3)
    # L2[4] -> PA 0x80009000，U=1，S 模式需要 SUM。
    li      t0, 0x200024df
    sd      t0, 32(t3)
    # L2[5] -> PA 0x8000a000，A/D 均为 0，adMode=fault 必须页错误。
    li      t0, 0x2000280f
    sd      t0, 40(t3)

    # 准备 U 页内容（通过 1GiB identity 超级页访问）。
    li      t0, 0x55667788
    li      t4, 0x80009000
    sd      t0, 0(t4)

    # S 模式页错误委托 + stvec。
    li      t0, 0x0000b000          # medeleg: cause 12/13/15
    csrw    medeleg, t0
    la      t0, s_trap
    csrw    stvec, t0

    # satp.MODE=8(Sv39), ASID=0, PPN=0x80004。
    li      t0, 0x8000000000080004
    csrw    satp, t0
    sfence.vma

    # mret 进入 S，mstatus.MPP=S。
    li      t0, 0x00000800
    csrw    mstatus, t0
    la      t0, s_entry
    csrw    mepc, t0
    mret

s_entry:
    # 1) 4KiB 页 walk + TLB refill。
    li      s9, 0
    li      t0, 0x40000000
    li      t1, 0x1122334455667788
    sd      t1, 0(t0)
    ld      t2, 0(t0)
    bne     t1, t2, fail1
    # 通过物理别名确认写入落到了 PA 0x80007000。
    li      t3, 0x80007000
    ld      t4, 0(t3)
    bne     t1, t4, fail2

    # 2) 未映射页 load page fault(cause=13)。
    li      t0, 0x40002000
    ld      t1, 0(t0)
    li      t0, 1
    bne     s9, t0, fail3
    li      t0, 13
    bne     s10, t0, fail4
    li      t0, 0x40002000
    bne     s11, t0, fail5

    # 3) 只读页 store page fault(cause=15)。
    li      s9, 0
    li      t0, 0x40003000
    sd      zero, 0(t0)
    li      t0, 1
    bne     s9, t0, fail6
    li      t0, 15
    bne     s10, t0, fail7
    li      t0, 0x40003000
    bne     s11, t0, fail8

    # 4) U 页在 SUM=0 时 load 页错误，SUM=1 后成功。
    li      s9, 0
    li      t0, 0x40004000
    ld      t1, 0(t0)
    li      t0, 1
    bne     s9, t0, fail9
    li      t0, 13
    bne     s10, t0, fail10
    li      t0, 0x00040000         # sstatus.SUM
    csrs    sstatus, t0
    csrr    t3, sstatus
    and     t3, t3, t0
    beqz    t3, fail19             # SUM 未写入
    li      t0, 0x40004000
    ld      t1, 0(t0)
    li      t0, 1
    bne     s9, t0, fail20         # SUM=1 仍然页错误
    li      t2, 0x55667788
    bne     t1, t2, fail11

    # 5) A=0/D=0 的页在 adMode=fault 下 load 页错误。
    li      s9, 0
    li      t0, 0x40005000
    ld      t1, 0(t0)
    li      t0, 1
    bne     s9, t0, fail12
    li      t0, 13
    bne     s10, t0, fail13
    li      t0, 0x40005000
    bne     s11, t0, fail14

    # 6) 非规范 Sv39 虚拟地址：bit63:39 不是 bit38 的符号扩展。
    li      s9, 0
    li      t0, 0x0000008000000000
    ld      t1, 0(t0)
    li      t0, 1
    bne     s9, t0, fail15
    li      t0, 13
    bne     s10, t0, fail16
    li      t0, 0x0000008000000000
    bne     s11, t0, fail17

    # 7) 再次访问 4KiB 页，验证 TLB 命中路径仍正确。
    li      t0, 0x40000000
    ld      t1, 0(t0)
    li      t2, 0x1122334455667788
    bne     t1, t2, fail18

    li      a0, 0
    .word   0x0000000b

# S 模式页错误处理：记录 cause/tval/次数，跳过故障指令后返回。
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
fail17: li a0, 17; .word 0x0000000b
fail18: li a0, 18; .word 0x0000000b
fail19: li a0, 19; .word 0x0000000b
fail20: li a0, 20; .word 0x0000000b
