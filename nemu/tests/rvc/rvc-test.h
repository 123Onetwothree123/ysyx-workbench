/*
 * RV32C/RV64C 定向测试共用汇编框架。
 *
 * 所有测试以 a0=0 结束（NEMU 的 custom-0 停机约定），失败时 a0 非零。
 * 通用 trap handler 会核对 mcause/mepc/mtval，并按 exp_skip 跳过出错
 * 指令；因此非法编码、断点、访问异常等测试都能继续执行并给出明确结果。
 */
#ifndef __RVC_TEST_H__
#define __RVC_TEST_H__

#if __riscv_xlen == 64
# define RVC_LD      ld
# define RVC_ST      sd
# define RVC_WORD    .dword
#else
# define RVC_LD      lw
# define RVC_ST      sw
# define RVC_WORD    .word
#endif

// 测试入口：设置栈和 M 模式 trap 向量。
.macro RVC_TEST_BEGIN
  .section .text
  .globl _start
_start:
  la      sp, rvc_stack_top
  la      t0, rvc_trap_handler
  csrw    mtvec, t0
  li      a0, 0
.endm

/*
 * 设置下一次陷入的预期值。
 *   cause: mcause
 *   epc:   触发指令地址（标签）
 *   tval:  mtval 立即数
 *   skip:  从 mepc 前进多少字节继续执行（可为负，用于越过内存末尾）
 */
.macro RVC_EXPECT cause, epc, tval, skip
  li      t0, \cause
  la      t1, rvc_exp_cause
  RVC_ST  t0, 0(t1)
  la      t0, \epc
  la      t1, rvc_exp_epc
  RVC_ST  t0, 0(t1)
  li      t0, \tval
  la      t1, rvc_exp_tval
  RVC_ST  t0, 0(t1)
  li      t0, \skip
  la      t1, rvc_exp_skip
  RVC_ST  t0, 0(t1)
.endm

// 与 RVC_EXPECT 相同，但 epc 是绝对地址常量而不是可重定位标签。
.macro RVC_EXPECT_ABS cause, epc, tval, skip
  li      t0, \cause
  la      t1, rvc_exp_cause
  RVC_ST  t0, 0(t1)
  li      t0, \epc
  la      t1, rvc_exp_epc
  RVC_ST  t0, 0(t1)
  li      t0, \tval
  la      t1, rvc_exp_tval
  RVC_ST  t0, 0(t1)
  li      t0, \skip
  la      t1, rvc_exp_skip
  RVC_ST  t0, 0(t1)
.endm

// 测试结尾：统一的 GOOD/BAD trap 出口。
.macro RVC_TEST_END
rvc_pass:
pass:
  li      a0, 0
  j       rvc_halt
rvc_fail:
fail:
  li      a0, 1
  j       rvc_halt
rvc_halt:
  .word   0x0000000b
.endm

// 通用异常入口：校验现场后跳过故障指令。
.macro RVC_TRAP_HANDLER
  .balign 4
  .globl rvc_trap_handler
rvc_trap_handler:
  csrr    t0, mcause
  la      t1, rvc_exp_cause
  RVC_LD  t1, 0(t1)
  bne     t0, t1, rvc_trap_bad

  csrr    t0, mepc
  la      t1, rvc_exp_epc
  RVC_LD  t1, 0(t1)
  bne     t0, t1, rvc_trap_bad

  csrr    t0, mtval
  la      t1, rvc_exp_tval
  RVC_LD  t1, 0(t1)
  bne     t0, t1, rvc_trap_bad

  csrr    t0, mepc
  la      t1, rvc_exp_skip
  RVC_LD  t1, 0(t1)
  add     t0, t0, t1
  csrw    mepc, t0
  mret

rvc_trap_bad:
  li      a0, 2
  j       rvc_halt
.endm

// 每个测试文件自带一份预期值存储和栈。
.macro RVC_TEST_DATA
  .section .data
  .balign 8
  .globl rvc_exp_cause
rvc_exp_cause:  RVC_WORD 0
  .globl rvc_exp_epc
rvc_exp_epc:    RVC_WORD 0
  .globl rvc_exp_tval
rvc_exp_tval:   RVC_WORD 0
  .globl rvc_exp_skip
rvc_exp_skip:   RVC_WORD 0

  .balign 16
  .space  1024
  .globl rvc_stack_top
rvc_stack_top:
.endm

#endif
