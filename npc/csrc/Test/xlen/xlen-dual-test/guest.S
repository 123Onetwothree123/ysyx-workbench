# XLEN 双模式 SDB / mtrace 回归 guest。
#
# RV64：
#   * t0/t1 保存 64 位高值，t1 还覆盖 bit31 符号扩展；
#   * 在 PA 0x80001000 写 64 位常量，供 SDB x/read64 读取；
#   * 把 mtvec/mepc 写成 4GiB 以上的值并读回，验证 DiffTest CSR 高半区；
#   * 建立 Sv39 映射，把 VA 0x1_0000_0000 映射到代码页、
#     VA 0x1_0000_2000 映射到 PA 0x80002000；
#   * 跳到 4GiB 以上的高 VA 执行，并在高地址 store/halt，
#     从而同时验证 64 位 PC、GPR 和 mtrace 地址/数据。
#
# RV32：
#   * 同样的测试流程退化为 32 位寄存器、32 位地址和 4B store。
    .section .text
    .globl _start
_start:
    li      sp, 0x80008000

.if __riscv_xlen == 64
    # 64 位寄存器/立即数：SDB p $s1 / p $s2 必须完整显示。
    # s1/s2 不被后续地址计算和高地址路径复用。
    li      s1, 0x123456789abcdef0
    li      s2, 0xffffffff80000000
    li      s0, 0x0000000100000000

    # SDB 低地址内存视图使用：PA 0x80001000 = 0x1122334455667788。
    li      t2, 0x80001000
    li      t3, 0x1122334455667788
    sd      t3, 0(t2)

    # 清空 root/L1/L2 三张页表：0x80004000..0x80006fff。
    li      t0, 0x80004000
    li      t1, 0x80007000
1:
    sd      zero, 0(t0)
    addi    t0, t0, 8
    bltu    t0, t1, 1b

    # root[2] = 1GiB identity 超级页，覆盖低地址代码和页表。
    li      t0, 0x200000cf
    li      t1, 0x80004000
    sd      t0, 16(t1)
    # root[4] -> L1，L1[0] -> L2，选择 VA 0x1_0000_0000 所在分支。
    li      t0, 0x20001401
    sd      t0, 32(t1)
    li      t2, 0x80005000
    li      t0, 0x20001801
    sd      t0, 0(t2)
    li      t3, 0x80006000
    # L2[0] -> 代码页 PA 0x80000000；L2[2] -> 数据页 PA 0x80002000。
    li      t0, 0x200000cf
    sd      t0, 0(t3)
    li      t0, 0x200008cf
    sd      t0, 16(t3)

    # DiffTest 的 CSR 快照必须保留完整 64 位：先把 mtvec/mepc 写成
    # 4GiB 以上的值并读回，再安装真正的入口。
    li      t0, 0x100002344
    csrw    mtvec, t0
    csrr    t5, mtvec
    bne     t5, t0, fail_csr_hi
    li      t0, 0x100003400
    csrw    mepc, t0
    csrr    t5, mepc
    bne     t5, t0, fail_csr_hi

    # S 模式页错误处理：任何意外 trap 都以 BAD 结束，避免静默跑飞。
    la      t0, trap
    csrw    stvec, t0
    # satp.MODE=8(Sv39), PPN=0x80004；然后 mret 进入 S。
    li      t0, 0x8000000000080004
    csrw    satp, t0
    sfence.vma
    li      t0, 0x00000800
    csrw    mstatus, t0
    la      t0, s_entry
    csrw    mepc, t0
    mret

s_entry:
    # 计算 high_body 的高 VA 别名：PA 落在代码页，VA = 4GiB + offset。
    la      t0, high_body
    li      t1, 0x80000000
    sub     t0, t0, t1
    li      t1, 0x100000000
    add     t0, t0, t1
    jalr    zero, t0, 0

high_body:
    # 高 VA store：mtrace 必须打印 Addr=0x100002000、Width=8B。
    li      t2, 0x100002000
    li      t3, 0x8877665544332211
    sd      t3, 0(t2)
    ld      t4, 0(t2)
    bne     t3, t4, fail_high
    li      a0, 0
    .word   0x0000000b

fail_high:
    li      a0, 9
    .word   0x0000000b

fail_csr_hi:
    li      a0, 17
    .word   0x0000000b

trap:
    li      a0, 15
    .word   0x0000000b

.else
    # RV32：32 位高半区寄存器与 4B 内存视图。s1/s2 同样避免被后续代码复用。
    li      s1, 0x89abcdef
    li      s2, 0x80000000
    li      s0, 0x10000000
    li      t2, 0x80001000
    li      t3, 0x12345678
    sw      t3, 0(t2)
    lw      t4, 0(t2)
    bne     t3, t4, fail32
    li      a0, 0
    .word   0x0000000b
fail32:
    li      a0, 1
    .word   0x0000000b
.endif
