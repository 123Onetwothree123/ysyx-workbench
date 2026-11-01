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
#define FPRS(f) \
      f( 0) f( 1) f( 2) f( 3) f( 4) f( 5) f( 6) f( 7) \
      f( 8) f( 9) f(10) f(11) f(12) f(13) f(14) f(15) \
      f(16) f(17) f(18) f(19) f(20) f(21) f(22) f(23) \
      f(24) f(25) f(26) f(27) f(28) f(29) f(30) f(31)
#define FPUSH(n) fsd concat(f, n), OFFSET_FPR(n)(sp);
#define FPOP(n)  fld concat(f, n), OFFSET_FPR(n)(sp);
#define OFFSET_FPR(n) ((NR_REGS * XLEN) + (n) * 8)
#define OFFSET_FCSR   ((NR_REGS * XLEN) + 32 * 8)
#define OFFSET_CAUSE  (OFFSET_FCSR + 8)
#define OFFSET_STATUS (OFFSET_CAUSE + XLEN)
#define OFFSET_EPC    (OFFSET_STATUS + XLEN)
#define CONTEXT_SIZE  (OFFSET_EPC + XLEN)
#else
#define CONTEXT_SIZE  ((NR_REGS + 3 + 1) * XLEN)
#define OFFSET_CAUSE  ((NR_REGS + 0) * XLEN)
#define OFFSET_STATUS ((NR_REGS + 1) * XLEN)
#define OFFSET_EPC    ((NR_REGS + 2) * XLEN)
#endif
#define OFFSET_SP     ( 2 * XLEN)

.align 3
.globl __am_asm_trap
__am_asm_trap:
  addi sp, sp, -CONTEXT_SIZE

  MAP(REGS, PUSH)

#ifdef AM_RV64GC
  # 浮点保存要求 mstatus.FS!=Off；若软件关掉 FS 后陷入，先恢复 FS。
  csrr t0, mstatus
  li t1, 0x6000
  and t0, t0, t1
  bnez t0, .Lfs_ready
  csrs mstatus, t1
.Lfs_ready:
  .option push
  .option norvc
  MAP(FPRS, FPUSH)
  frcsr t0
  STORE t0, OFFSET_FCSR(sp)
  .option pop
#endif

  csrr t0, mcause
  csrr t1, mstatus
  csrr t2, mepc

  STORE t0, OFFSET_CAUSE(sp)
  STORE t1, OFFSET_STATUS(sp)
  STORE t2, OFFSET_EPC(sp)

  mv a0, sp
  call __am_irq_handle
  mv sp, a0 #使用__am_irq_handle返回的新Context

  LOAD t1, OFFSET_STATUS(sp)
  LOAD t2, OFFSET_EPC(sp)
  csrw mstatus, t1
  csrw mepc, t2

#ifdef AM_RV64GC
  # 先写回包含 FS 的 mstatus，再恢复 fcsr/f0-f31。
  LOAD t0, OFFSET_FCSR(sp)
  .option push
  .option norvc
  fscsr t0
  MAP(FPRS, FPOP)
  .option pop
#endif

  MAP(REGS, POP)

  addi sp, sp, CONTEXT_SIZE
  mret