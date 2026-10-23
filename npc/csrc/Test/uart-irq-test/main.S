// direct-NPC UART RX + PLIC source 10 的裸机回归。
// 运行在 RV32 M/S 模式：先验证空 UART 的 LSR 可读（不得 DECERR），
// 再配置 PLIC source 10 和 UART IER，mret 到 S 模式等待外部中断。
// 测试平台在运行中注入字符 'Z'；中断处理程序必须 claim 到 10、
// 读到 LSR.DataReady 和 RBR='Z'，然后 complete 并 GOOD halt。

.section .text
.globl _start
_start:
  // 空 FIFO：LSR 低字节应为 0x60（THRE/TEMT=1，DataReady=0）。
  li t0, 0x10000005
  lbu t1, 0(t0)
  andi t1, t1, 1
  bnez t1, fail_m

  // PLIC source 10：priority=1、S 上下文 enable bit10、threshold=0。
  li t0, 0x0c000028
  li t1, 1
  sw t1, 0(t0)
  li t0, 0x0c002080
  li t1, 1 << 10
  sw t1, 0(t0)
  li t0, 0x0c201000
  sw zero, 0(t0)

  // UART IER.RX=1。
  li t0, 0x10000001
  li t1, 1
  sb t1, 0(t0)

  // 委托 S 外部中断，mret 到 S 模式。
  li t0, 1 << 9
  csrw mideleg, t0
  csrw mie, t0
  la t0, s_entry
  csrw mepc, t0
  csrw mstatus, zero
  li t0, 0x0800
  csrs mstatus, t0
  mret

fail_m:
  li t0, 0x80002008
  li t1, 0x0bad
  sw t1, 0(t0)
  li a0, 1
  .word 0x0000000b

s_entry:
  la t0, s_trap
  csrw stvec, t0
  li t0, 2
  csrs sstatus, t0
1:
  j 1b

s_trap:
  csrr t0, scause
  li t1, 0x80000009
  bne t0, t1, fail

  // claim 必须是 source 10。
  li t0, 0x0c201004
  lw t1, 0(t0)
  li t2, 0x80002004
  sw t1, 0(t2)
  li t2, 10
  bne t1, t2, fail

  // LSR.DataReady 必须为 1，RBR 必须返回注入字符 'Z'。
  li t0, 0x10000005
  lbu t1, 0(t0)
  andi t1, t1, 1
  beqz t1, fail
  li t0, 0x10000000
  lbu t1, 0(t0)
  li t2, 'Z'
  bne t1, t2, fail

  // complete，然后写成功标记并 halt。
  li t0, 0x0c201004
  li t1, 10
  sw t1, 0(t0)
  li t0, 0x80002008
  li t1, 0xc0de
  sw t1, 0(t0)
  li a0, 0
  .word 0x0000000b

fail:
  li t0, 0x80002008
  li t1, 0x0bad
  sw t1, 0(t0)
  li a0, 1
  .word 0x0000000b
2:
  j 2b
