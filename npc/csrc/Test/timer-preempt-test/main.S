/*
 * direct-NPC 的 CLINT -> STIP -> S 模式时钟中断回归。
 *
 * 验证内容：
 *   1. mtimecmp 到期后 CLINT timer_irq 生效，TIMER_PATH=direct_stip 把它
 *      映射为 mip.STIP，而不是 machine 的 MTIP；
 *   2. mideleg.STIP + mie.STIE + sstatus.SIE 共同使能后，中断进入 S 模式，
 *      scause = 0x80000005；
 *   3. 进入 trap 时 sstatus.SIE=0、SPIE=旧 SIE=1、SPP=1；
 *   4. mret 后 mstatus.MIE=MPIE=1，sret 后 sstatus.SIE 恢复为 1；
 *   5. 中断处理程序重新装载 mtimecmp，前台循环的寄存器和进度没有被破坏。
 *
 * 测试不使用任何用户态 yield：前台是一个普通的自增循环，只有时钟
 * 中断能打断它。连续 8 次中断后写入 GOOD trap。
 */

.section .text
.globl _start
_start:
  # 测试栈放在 RAM 内，远离镜像本身。
  li sp, 0x80100000

  # 初始 mtimecmp = mtime + 2000 个 CPU 周期，缩短 RTL 回归时间。
  li t0, 0x0200bff8          # CLINT mtime
  lw t1, 0(t0)
  lw t2, 4(t0)
  addi t1, t1, 2000
  li t0, 0x02004004          # 先写高 32 位，避免比较值短暂落到 mtime 之下
  sw t2, 0(t0)
  li t0, 0x02004000
  sw t1, 0(t0)

  # 只委托 S 模式时钟中断（STIP, cause=5）；异常不委托，后面的
  # S 模式 ecall 会进入 M 模式，用来检查 mstatus.MIE/MPIE。
  li t0, 1 << 5
  csrw mideleg, t0
  csrw mie, t0

  la t0, s_trap
  csrw stvec, t0
  la t0, m_trap
  csrw mtvec, t0

  # MPP=S，MPIE=1；mret 后 MIE=1，并进入 S 模式。
  li t0, (1 << 11) | (1 << 7)
  csrw mstatus, t0
  la t0, s_entry
  csrw mepc, t0
  mret

s_entry:
  # S 模式不能读 mstatus/mie（它们是 M 级 CSR）。先执行一次 ecall，
  # 由 M 模式陷阱入口检查 mret 后的 MIE/MPIE/MPP；返回后再打开 SIE。
  ecall
after_ecall:
  # sie.STIE 必须可见（bound 视图映射到 mie 的 STIE 位）。
  csrr t0, sie
  andi t0, t0, 1 << 5
  beqz t0, fail

  li s0, 0                   # 前台循环进度（中断必须保留它）
  li s1, 0                   # 已处理的时钟中断次数

  # 打开 S 模式全局中断，之后普通循环会被异步抢占。
  csrs sstatus, 2

loop:
  addi s0, s0, 1
  li t0, 8
  beq s1, t0, done
  j loop
loop_end:

s_trap:
  # 中断 cause 必须是 0x80000005（S 模式时钟中断）。
  csrr t0, scause
  li t1, 0x80000005
  bne t0, t1, fail

  # trap 现场：SIE=0，SPIE=旧 SIE=1，SPP=1（来自 S 模式）。
  csrr t0, sstatus
  andi t1, t0, 1 << 1
  bnez t1, fail
  andi t1, t0, 1 << 5
  beqz t1, fail
  andi t1, t0, 1 << 8
  beqz t1, fail

  # CLINT 仍是电平中断，重新装载前 sip.STIP 应保持为 1。
  csrr t0, sip
  andi t0, t0, 1 << 5
  beqz t0, fail

  # sepc 必须落在前台循环内部，证明打断的是普通指令流。
  csrr t0, sepc
  la t1, loop
  bltu t0, t1, fail
  la t1, loop_end
  bgeu t0, t1, fail

  # 重新装载 mtimecmp = mtime + 2000，先高后低。
  li t0, 0x0200bff8
  lw t1, 0(t0)
  lw t2, 4(t0)
  addi t1, t1, 2000
  li t0, 0x02004004
  sw t2, 0(t0)
  li t0, 0x02004000
  sw t1, 0(t0)

  addi s1, s1, 1
  sret

done:
  # 8 次中断后 sret 应已恢复 SIE=1。
  csrr t0, sstatus
  andi t1, t0, 1 << 1
  beqz t1, fail

  # 前台循环至少执行过，且 8 次中断都完成了重新装载。
  li t0, 8
  bltu s0, t0, fail
  bne s1, t0, fail

  li a0, 0
  .word 0x0000000b          # custom-0: GOOD trap

# 从 S 模式 ecall 进入的 M 模式陷阱。检查 MRET 后的 MIE/MPIE/MPP，
# 跳过 ecall 后返回 S 模式。若这里收到其它 cause，说明异常路径异常。
m_trap:
  csrr t0, mcause
  li t1, 9
  bne t0, t1, fail_m

  csrr t0, mstatus
  andi t1, t0, 1 << 3        # MIE 在进入 M trap 后应为 0
  bnez t1, fail_m
  andi t1, t0, 1 << 7        # MPIE 保存的是 trap 前的 MIE=1
  beqz t1, fail_m
  srli t1, t0, 11
  andi t1, t1, 3             # MPP 保存 trap 前的 S 模式
  li t2, 1
  bne t1, t2, fail_m

  csrr t0, mepc
  addi t0, t0, 4             # 跳过 ecall
  csrw mepc, t0
  mret

fail_m:
  li a0, 1
  .word 0x0000000b          # custom-0: BAD trap

fail:
  li a0, 1
  .word 0x0000000b          # custom-0: BAD trap
1:
  j 1b
