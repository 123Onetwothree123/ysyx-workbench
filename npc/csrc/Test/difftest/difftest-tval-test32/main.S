    # RV32 trap tval DiffTest 回归：illegal / 非对齐 / ecall 的 mtval。
    # cacheable 非对齐 load 按新策略拆分完成并校验数据。纯 M 模式、
    # 无 store；MMIO access fault 的 tval 由 unit 测试覆盖。
    .section .text
    .globl _start
_start:
    la      t0, m_trap
    csrw    mtvec, t0

    # 1) illegal instruction
    li      s9, 0
    la      s0, illegal_site
illegal_site:
    .word   0xffffffff
    li      t0, 1
    bne     s9, t0, fail1
    li      t0, 2
    bne     s10, t0, fail2
    li      t0, 0xffffffff
    bne     s11, t0, fail3

    # 非对齐 load 在新策略下不再 trap，其 tval 由 unit 的
    # LSUMisalignedTest 覆盖；这里保留 illegal / ecall 的 tval 检查。
    # 2) ecall（M 模式 cause=11，DUT mtval=指令 PC）
    li      s9, 0
    la      s0, ecall_site
ecall_site:
    ecall
    li      t0, 1
    bne     s9, t0, fail7
    li      t0, 11
    bne     s10, t0, fail8
    bne     s11, s0, fail9

    li      a0, 0
    .word   0x0000000b

m_trap:
    csrr    s10, mcause
    csrr    s11, mtval
    addi    s9, s9, 1
    csrr    t0, mepc
    addi    t0, t0, 4
    csrw    mepc, t0
    mret

fail1: li a0, 1; .word 0x0000000b
fail2: li a0, 2; .word 0x0000000b
fail3: li a0, 3; .word 0x0000000b
fail4: li a0, 4; .word 0x0000000b
fail5: li a0, 5; .word 0x0000000b
fail7: li a0, 7; .word 0x0000000b
fail8: li a0, 8; .word 0x0000000b
fail9: li a0, 9; .word 0x0000000b
