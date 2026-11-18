// full-SoC (ysyxSoCFull) UART 中断路径回归。
// 程序从 MROM 0x30000000 启动，只使用寄存器，不依赖可写内存。
// 目标：打开 APBUart16550 的 IER.THRE，进入 S 模式，等待 CPU 内部 PLIC
// source 10 的外部中断；中断处理程序必须 claim 到 10，并从 UART IIR
// 读到 THRE 编码，证明 full-SoC 的 UART 中断确实经过 PLIC，而不是被
// 伪装成一根外部中断线。

.equ UART_RBR,      0x10000000
.equ UART_THR,      0x10000000
.equ UART_IER,      0x10000001
.equ UART_IIR,      0x10000002
.equ UART_LCR,      0x10000003
.equ UART_LSR,      0x10000005
.equ PLIC_PRIO10,   0x0c000028
.equ PLIC_SENABLE,  0x0c002080
.equ PLIC_STHRESH,  0x0c201000
.equ PLIC_CLAIM,    0x0c201004

.section .text
.globl _start
_start:
  la t0, fail_m
  csrw mtvec, t0

  // 空 UART：LSR.THRE/TEMT 应为 1，DataReady 应为 0。
  li t0, UART_LSR
  lbu t1, 0(t0)
  andi t2, t1, 0x60
  li t3, 0x60
  bne t2, t3, fail_m
  andi t2, t1, 1
  bnez t2, fail_m

  // 设置 divisor=1，使 opencores 发送器保持使能；LCR 回到 8n1。
  li t0, UART_LCR
  li t1, 0x83
  sb t1, 0(t0)
  li t0, UART_THR
  li t1, 1
  sb t1, 0(t0)
  li t0, UART_IER
  sb zero, 0(t0)
  li t0, UART_LCR
  li t1, 0x03
  sb t1, 0(t0)

  // PLIC source 10：priority=1、S 上下文 enable bit10、threshold=0。
  li t0, PLIC_PRIO10
  li t1, 1
  sw t1, 0(t0)
  li t0, PLIC_SENABLE
  li t1, 1 << 10
  sw t1, 0(t0)
  li t0, PLIC_STHRESH
  sw zero, 0(t0)

  // UART IER.THRE=1，进入发送中断阶段。
  li t0, UART_IER
  li t1, 2
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

  // full-SoC 的 UART 中断必须由 PLIC source 10 提供。
  li t0, PLIC_CLAIM
  lw t1, 0(t0)
  li t2, 10
  bne t1, t2, fail

  // IIR 必须报告 THRE（低 4 位 0x02）。
  li t0, UART_IIR
  lbu t1, 0(t0)
  andi t1, t1, 0x0f
  li t2, 0x02
  bne t1, t2, fail

  // THR 空闲时 LSR.THRE 必须为 1。
  li t0, UART_LSR
  lbu t1, 0(t0)
  andi t1, t1, 0x20
  beqz t1, fail

  // 关中断、complete，报告 GOOD。
  li t0, UART_IER
  sb zero, 0(t0)
  li t0, PLIC_CLAIM
  li t1, 10
  sw t1, 0(t0)
  li a0, 0
  .word 0x0000000b

fail:
  li a0, 1
  .word 0x0000000b
2:
  j 2b
