// direct-NPC UART RX/TX 中断 + PLIC source 10 的裸机回归。
// 运行在 RV32 M/S 模式：
//   1. 空 UART 的 LSR 必须是 0x60（THRE/TEMT=1，DataReady=0）；
//   2. 配置 PLIC source 10 与 IER.RX，等待测试平台注入的 'Z'，在 S 模式
//      外部中断里 claim 到 10，读 LSR/RBR 并 complete；
//   3. 切换到 IER.THRE，确认空闲 THR 产生 IIR=THRE 中断；
//   4. 写 THR 后观察 LSR.THRE/TEMT 拉低，再等待二者恢复并产生下一次
//      THRE 中断，验证写 THR 后的中断重臂行为。
// 三个阶段都必须通过 PLIC claim 到 source 10，证明中断确实经过 CPU PLIC，
// 而不是只靠一根直连外部中断线。

.equ UART_RBR,      0x10000000
.equ UART_THR,      0x10000000
.equ UART_IER,      0x10000001
.equ UART_IIR,      0x10000002
.equ UART_LSR,      0x10000005
.equ PLIC_PRIO10,   0x0c000028
.equ PLIC_PENDING,  0x0c001000
.equ PLIC_SENABLE,  0x0c002080
.equ PLIC_STHRESH,  0x0c201000
.equ PLIC_CLAIM,    0x0c201004
.equ MARKER,        0x80002008

.section .text
.globl _start
_start:
  li s1, 0

  // 空 FIFO：LSR 低字节应为 0x60（THRE/TEMT=1，DataReady=0）。
  li t0, UART_LSR
  lbu t1, 0(t0)
  andi t2, t1, 0x60
  li t3, 0x60
  bne t2, t3, fail_m
  andi t2, t1, 1
  bnez t2, fail_m

  // PLIC source 10：priority=1、S 上下文 enable bit10、threshold=0。
  li t0, PLIC_PRIO10
  li t1, 1
  sw t1, 0(t0)
  li t0, PLIC_SENABLE
  li t1, 1 << 10
  sw t1, 0(t0)
  li t0, PLIC_STHRESH
  sw zero, 0(t0)

  // UART IER.RX=1。
  li t0, UART_IER
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
  li t0, MARKER
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

  // 每次中断都必须 claim 到 source 10。
  li t0, PLIC_CLAIM
  lw t1, 0(t0)
  li t2, MARKER + 4
  sw t1, 0(t2)
  li t2, 10
  bne t1, t2, fail

  beqz s1, rx_stage
  li t2, 1
  beq s1, t2, tx_first_stage
  li t2, 2
  beq s1, t2, tx_rearm_stage
  j fail

rx_stage:
  // LSR.DataReady 必须为 1，RBR 必须返回注入字符 'Z'。
  li t0, UART_LSR
  lbu t1, 0(t0)
  andi t1, t1, 1
  beqz t1, fail
  li t0, UART_RBR
  lbu t1, 0(t0)
  li t2, 'Z'
  bne t1, t2, fail

  // complete RX，然后打开 IER.THRE，进入发送中断阶段。
  li t0, PLIC_CLAIM
  li t1, 10
  sw t1, 0(t0)
  li t0, UART_IER
  li t1, 2
  sb t1, 0(t0)
  li s1, 1
  sret

tx_first_stage:
  // 空闲 THR + IER.THRE 必须给出 IIR 的 THRE 编码（低 4 位 0x02）。
  li t0, UART_IIR
  lbu t1, 0(t0)
  andi t1, t1, 0x0f
  li t2, 0x02
  bne t1, t2, fail

  // 此时发送器完全空闲，LSR.THRE 与 TEMT 都应为 1。
  li t0, UART_LSR
  lbu t1, 0(t0)
  andi t1, t1, 0x60
  li t2, 0x60
  bne t1, t2, fail

  // complete 第一个 THRE 中断，然后写 THR 触发新的发送活动。
  li t0, PLIC_CLAIM
  li t1, 10
  sw t1, 0(t0)
  li t0, UART_THR
  li t1, 'T'
  sb t1, 0(t0)

  // 写 THR 后 THRE/TEMT 必须拉低；模型用固定发送窗口保证它可被观察到。
  li t0, UART_LSR
  lbu t1, 0(t0)
  andi t1, t1, 0x60
  bnez t1, fail

  li s1, 2
  sret

tx_rearm_stage:
  // THR 重新变空后应再次产生 THRE 中断。
  li t0, UART_IIR
  lbu t1, 0(t0)
  andi t1, t1, 0x0f
  li t2, 0x02
  bne t1, t2, fail

  // 轮询等待 LSR.THRE/TEMT 都恢复，验证发送窗口结束后的状态。
  li t0, UART_LSR
  li t3, 10000
wait_idle:
  lbu t1, 0(t0)
  andi t1, t1, 0x60
  li t2, 0x60
  beq t1, t2, idle_seen
  addi t3, t3, -1
  bnez t3, wait_idle
  j fail

idle_seen:
  // 关中断并 complete，报告 GOOD。
  li t0, UART_IER
  sb zero, 0(t0)
  li t0, PLIC_CLAIM
  li t1, 10
  sw t1, 0(t0)
  li t0, MARKER
  li t1, 0xc0de
  sw t1, 0(t0)
  li a0, 0
  .word 0x0000000b

fail:
  li t0, MARKER
  li t1, 0x0bad
  sw t1, 0(t0)
  li a0, 1
  .word 0x0000000b
2:
  j 2b
