#define concat_temp(x, y) x ## y
#define concat(x, y) concat_temp(x, y)
#define MAP(c, f) c(f)

#if __riscv_xlen == 32
#define LOAD  lw
#define STORE sw
#define XLEN  4
#else
#define LOAD  ld
#define STORE sd
#define XLEN  8
#endif

#define REGS_LO16(f) \
      f( 1)       f( 3) f( 4) f( 5) f( 6) f( 7) f( 8) f( 9) \
f(10) f(11) f(12) f(13) f(14) f(15)
#ifndef __riscv_e
#define REGS_HI16(f) \
                                    f(16) f(17) f(18) f(19) \
f(20) f(21) f(22) f(23) f(24) f(25) f(26) f(27) f(28) f(29) \
f(30) f(31)
#define NR_REGS 32
#else
#define REGS_HI16(f)
#define NR_REGS 16
#endif

#define REGS(f) REGS_LO16(f) REGS_HI16(f)

#define PUSH(n) STORE concat(x, n), (n * XLEN)(sp);
#define POP(n)  LOAD  concat(x, n), (n * XLEN)(sp);

#ifdef AM_RV64GC
// RV64GC 目标保存 f0-f31 和 fcsr；f0 也是普通浮点寄存器，不能省略。
#define FPRS(f) \
      f( 0) f( 1) f( 2) f( 3) f( 4) f( 5) f( 6) f( 7) \
      f( 8) f( 9) f(10) f(11) f(12) f(13) f(14) f(15) \
      f(16) f(17) f(18) f(19) f(20) f(21) f(22) f(23) \
      f(24) f(25) f(26) f(27) f(28) f(29) f(30) f(31)
#define FPUSH(n) fsd concat(f, n), OFFSET_FPR(n)(sp);
#define FPOP(n)  fld concat(f, n), OFFSET_FPR(n)(sp);

#define OFFSET_FPR(n) ((NR_REGS * XLEN) + (n) * 8)
#define OFFSET_FCSR   ((NR_REGS * XLEN) + 32 * 8)
#define OFFSET_CAUSE  ((NR_REGS * XLEN) + 33 * 8)
#define OFFSET_STATUS (OFFSET_CAUSE + XLEN)
#define OFFSET_EPC    (OFFSET_STATUS + XLEN)
#define CONTEXT_SIZE  (OFFSET_EPC + XLEN)
#else
#define CONTEXT_SIZE  ((NR_REGS + 3) * XLEN)
#define OFFSET_CAUSE  ((NR_REGS + 0) * XLEN)
#define OFFSET_STATUS ((NR_REGS + 1) * XLEN)
#define OFFSET_EPC    ((NR_REGS + 2) * XLEN)
#endif
#define OFFSET_SP     ( 2 * XLEN)

.align 3
.globl __am_enter_supervisor
__am_enter_supervisor:
  # 从 M 模式进入 S 模式，返回地址仍保存在 ra 中。
  la t0, 1f
  csrw mepc, t0
  # MPP=S，MPIE=1，SUM=1，MXR=1；RV64 还要把 SXL/UXL 设为 2（64 位）。
  # RV64GC 额外把 FS 置 Dirty，保证 mret 到 S 模式后第一条浮点指令
  # 不会因为 mstatus.FS=Off 触发非法指令。
#if defined(AM_RV64GC)
  li t0, 0xa000c6880
#elif __riscv_xlen == 64
  li t0, 0xa000c0880
#else
  li t0, 0xc0880
#endif
  csrw mstatus, t0
  mret
1:
  ret

.align 3
.globl __am_asm_trap
__am_asm_trap:
  # sscratch=0 表示陷入前就在 S 模式内核栈上，此时不切换栈；
  # sscratch!=0 表示从 U 模式陷入，交换后 sp 指向该进程的内核栈顶。
  csrrw sp, sscratch, sp
  bnez sp, 1f
  csrr sp, sscratch
1:
  addi sp, sp, -CONTEXT_SIZE

  MAP(REGS, PUSH)

  # 寄存器保存宏不保存 x2，这里把陷入前的 sp 写入 gpr[2] 槽位。
  csrr t0, sscratch
  STORE t0, OFFSET_SP(sp)

#ifdef AM_RV64GC
  # 浮点保存要求 sstatus.FS!=Off。正常情况下 AM 已把 FS 置 Dirty；
  # 若软件显式关掉 FS 后陷入，这里先恢复 FS 再保存，避免在 trap
  # 入口递归触发非法指令。
  csrr t0, sstatus
  li t1, 0x6000
  and t0, t0, t1
  bnez t0, .Lfs_ready
  csrs sstatus, t1
.Lfs_ready:
  # 禁止把 f0 的保存/恢复压成 C.FLDSP/C.FSDSP：f0 是完整上下文的一部分，
  # 且部分实现把 C.FLDSP 的 rd=0 当作保留编码。这里统一用 32 位 F/D
  # 指令，C 扩展仍由其余代码使用。
  .option push
  .option norvc
  MAP(FPRS, FPUSH)
  frcsr t0
  STORE t0, OFFSET_FCSR(sp)
  .option pop
#endif

  csrr t0, scause
  csrr t1, sstatus
  csrr t2, sepc

  STORE t0, OFFSET_CAUSE(sp)
  STORE t1, OFFSET_STATUS(sp)
  STORE t2, OFFSET_EPC(sp)

  mv a0, sp
  call __am_irq_handle

  # a0 是下一个要恢复的上下文。
  # 下一次从 U 陷入时 sscratch 应指向内核栈顶；从 S 陷入时应为 0。
  LOAD t1, OFFSET_STATUS(a0)
  LOAD t2, OFFSET_EPC(a0)
  andi t0, t1, 0x100
  bnez t0, 1f
  addi t0, a0, CONTEXT_SIZE
  csrw sscratch, t0
  j 2f
1:
  csrw sscratch, zero
2:
  # 返回路径必须保持关中断，直到 sret 才根据 SPIE 重新开中断。
  # 否则时钟中断可能在恢复寄存器期间重入，覆盖当前上下文。
  li t0, -3
  and t1, t1, t0
  csrw sstatus, t1
  csrw sepc, t2
  mv sp, a0

#ifdef AM_RV64GC
  # 先写回包含 FS 的 sstatus，再恢复 fcsr/f0-f31；fld/fscsr 不会
  # 因 FS=Off 再次陷入。这里同样禁用 RVC，避免 f0 使用 C.FLDSP rd=0。
  LOAD t0, OFFSET_FCSR(sp)
  .option push
  .option norvc
  fscsr t0
  MAP(FPRS, FPOP)
  .option pop
#endif

  MAP(REGS, POP)

  # 恢复陷入前的 sp：用户进程是用户栈，内核线程是内核栈。
  LOAD sp, OFFSET_SP(sp)
  sret
