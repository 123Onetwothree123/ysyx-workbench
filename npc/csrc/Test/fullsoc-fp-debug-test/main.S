// full-SoC 浮点/trap 调试端口最小探针。
//
// 从 MROM 0x30000000 启动，只使用寄存器和两条必须成功的 FP 指令：
//   1. 打开 mstatus.FS，写 f2=1.5，执行 fadd.s 得到 f3=3.0；
//   2. 把 fcsr 写成 0x40（frm=2），随后读回确认；
//   3. 执行一条地址未对齐的 lw，要求 mcause=4、mtval=0x30000001；
//      trap handler 中再次读 f2/fcsr，确认异常没有破坏浮点状态。
//
// 本镜像同时服务于两个检查：
//   - 直接连 VysyxSoCFull 的端口级探针：halt 后读 debug_fpr/debug_fcsr/
//     debug_trap_tval，证明 full-SoC 顶层端口确实被驱动；
//   - NPC --diff：handler 对 mtval 的断言会迫使 REF 注入真实的 trap tval，
//     逐退休 f0-f31/fcsr 比对则覆盖浮点 DiffTest ABI。

.equ MROM_BASE, 0x30000000
.equ F1_5,     0x3fc00000
.equ F3_0,     0x40400000
.equ FCSR_VAL, 0x40

.section .text
.globl _start
_start:
  // FS=3（Dirty），允许访问浮点寄存器和 fcsr。
  li t0, 0x6000
  csrs mstatus, t0
  la t0, trap_handler
  csrw mtvec, t0

  // f2 = 1.5
  li t0, F1_5
  fmv.w.x f2, t0
  // f3 = f2 + f2 = 3.0
  fadd.s f3, f2, f2
  fmv.x.w t1, f3
  li t2, F3_0
  bne t1, t2, fail_fadd

  // f2 必须仍是 1.5，证明 FPR 写口真正落到物理寄存器。
  fmv.x.w t1, f2
  li t2, F1_5
  bne t1, t2, fail_fpr

  // fcsr 写 frm=2，flags 清零；随后读回。
  li t0, FCSR_VAL
  csrw fcsr, t0
  csrr t1, fcsr
  li t2, FCSR_VAL
  bne t1, t2, fail_fcsr

  // 地址未对齐 load：mcause=4，mtval=0x30000001。
  li t0, MROM_BASE
  lw t1, 1(t0)
  // 未陷入说明异常分类错误。
  j fail_no_trap

trap_handler:
  csrr t0, mcause
  li t1, 4
  bne t0, t1, fail_cause

  csrr t0, mtval
  li t1, MROM_BASE + 1
  bne t0, t1, fail_tval

  // trap 边界上 FPR/fcsr 不得被破坏。
  fmv.x.w t0, f2
  li t1, F1_5
  bne t0, t1, fail_fpr_trap
  csrr t0, fcsr
  li t1, FCSR_VAL
  bne t0, t1, fail_fcsr_trap

  li a0, 0
  j halt

fail_fadd:
  li a0, 1
  j halt
fail_fpr:
  li a0, 2
  j halt
fail_fcsr:
  li a0, 3
  j halt
fail_no_trap:
  li a0, 4
  j halt
fail_cause:
  li a0, 5
  j halt
fail_tval:
  li a0, 6
  j halt
fail_fpr_trap:
  li a0, 7
  j halt
fail_fcsr_trap:
  li a0, 8
  j halt

halt:
  .word 0x0000000b
