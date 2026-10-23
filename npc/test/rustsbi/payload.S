# 最小 S 模式 RustSBI 冒烟测试 payload。
#
# 覆盖：
#   1. next-stage 0x80200000 跳转（能跑到这里本身即证明）；
#   2. legacy console putchar      -> 'L'
#   3. DBCN console write          -> 'D'
#   4. Base get_spec_version       -> 'B'
#   5. Base probe DBCN             -> 'P'
#   6. TIME + MTIP->STIP 注入      -> 'T'
#   7. sPI + SSIP 注入             -> 'I'
#   8. 正常收尾                    -> "OK"
#
# 期望串口输出：LDBPTIOK
# 用 -DNPC_HALT 编译时，结尾用 NPC custom-0 halt 结束仿真；否则（QEMU）
# 在 WFI 循环中等待外部 timeout。

    .section .text.entry
    .globl _start
_start:
    la      sp, stack_top
    la      t0, trap_handler
    csrw    stvec, t0

    # legacy console putchar 'L'
    li      a7, 1
    li      a0, 'L'
    ecall

    # DBCN write "D"：a0=长度，a1=物理地址低半，a2=高半
    li      a7, 0x4442434e
    li      a6, 0
    li      a0, 1
    la      a1, msg_dbcn
    li      a2, 0
    ecall

    # Base get_spec_version，成功则打印 'B'
    li      a7, 0x10
    li      a6, 0
    ecall
    bnez    a0, fail
    li      a7, 1
    li      a0, 'B'
    ecall

    # Base probe DBCN，可用则打印 'P'
    li      a7, 0x10
    li      a6, 3
    li      a0, 0x4442434e
    ecall
    beqz    a1, fail
    li      a7, 1
    li      a0, 'P'
    ecall

    # 设置 timer = 2000（NPC 的 mtime 从 0 开始按周期递增）
    li      a7, 0x54494d45
    li      a6, 0
    li      a0, 2000
    ecall

    # 打开 S 定时器中断（STIE）和 S 软中断（SSIE），再开全局 SIE
    li      t0, 0x22
    csrs    sie, t0
    csrs    sstatus, 2

wait_loop:
    wfi
    j       wait_loop

    .align 4
trap_handler:
    csrr    t0, scause
    li      t1, 1
    slli    t1, t1, 63
    ori     t1, t1, 5
    beq     t0, t1, timer_irq
    li      t1, 1
    slli    t1, t1, 63
    ori     t1, t1, 1
    beq     t0, t1, software_irq
    j       fail

timer_irq:
    li      a7, 1
    li      a0, 'T'
    ecall

    # 清除 timer 并安排无限远的下一次
    li      a7, 0x54494d45
    li      a6, 0
    li      a0, -1
    ecall

    # 单 hart IPI：给自己发 S 模式软中断
    li      a7, 0x735049
    li      a6, 0
    li      a0, 1
    li      a1, 0
    ecall
    bnez    a0, fail
    csrr    t0, sepc
    addi    t0, t0, 4
    csrw    sepc, t0
    sret

software_irq:
    li      a7, 1
    li      a0, 'I'
    ecall
    li      t0, 2
    csrc    sip, t0

    # 输出 OK 并结束
    li      a7, 1
    li      a0, 'O'
    ecall
    li      a7, 1
    li      a0, 'K'
    ecall
    li      a7, 1
    li      a0, 10
    ecall
#ifdef NPC_HALT
    li      a0, 0
    .word   0x0000000b
#else
1:  wfi
    j       1b
#endif

fail:
    li      a7, 1
    li      a0, 'F'
    ecall
    li      a7, 1
    li      a0, 10
    ecall
#ifdef NPC_HALT
    li      a0, 1
    .word   0x0000000b
#else
1:  wfi
    j       1b
#endif

    .section .rodata
msg_dbcn:
    .byte   'D'

    .section .bss
    .align 4
    .space  4096
stack_top:

    .section .note.GNU-stack,"",@progbits
