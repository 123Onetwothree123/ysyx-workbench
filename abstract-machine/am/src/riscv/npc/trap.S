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

#define CONTEXT_SIZE  ((NR_REGS + 3) * XLEN)
#define OFFSET_SP     ( 2 * XLEN)
#define OFFSET_CAUSE  ((NR_REGS + 0) * XLEN)
#define OFFSET_STATUS ((NR_REGS + 1) * XLEN)
#define OFFSET_EPC    ((NR_REGS + 2) * XLEN)

.align 3
.globl __am_enter_supervisor
__am_enter_supervisor:
  # 从 M 模式进入 S 模式，返回地址仍保存在 ra 中。
  la t0, 1f
  csrw mepc, t0
  # MPP=S，MPIE=1，SUM=1，MXR=1；RV64 还要把 SXL/UXL 设为 2（64 位）。
#if __riscv_xlen == 64
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

  MAP(REGS, POP)

  # 恢复陷入前的 sp：用户进程是用户栈，内核线程是内核栈。
  LOAD sp, OFFSET_SP(sp)
  sret
