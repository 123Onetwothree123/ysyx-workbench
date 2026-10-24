# RV64GC 浮点上下文探针。
#
# int fp_probe(uint64_t tag)：
#   1. 给 f0-f31 写入与 tag/寄存器号相关的值（偶数号写 double，
#      奇数号写 float 并做 NaN-box），并写入一个可校验的 fcsr；
#   2. 在浮点寄存器保持脏值期间执行 ecall 让 AM 调度器切换上下文；
#   3. 返回后逐项比对 f0-f31 和 fcsr。
# 返回 0 表示全部一致；返回 n+1 表示 f<n> 不一致，33 表示 fcsr 不一致。
# 这样 yield 或时钟中断切换后，任何漏保存/串上下文都会被抓到。

  .text
  .globl fp_probe
  .type fp_probe, @function
fp_probe:
  # 112 字节栈帧保持 16 字节对齐；保存 ABI 规定的 callee-saved 浮点
  # 寄存器（f8/f9/f18-f27）以及调用者的 fcsr。
  addi sp, sp, -112
  frcsr t0
  sd   t0, 96(sp)
  fsd  f8,   0(sp)
  fsd  f9,   8(sp)
  fsd  f18, 16(sp)
  fsd  f19, 24(sp)
  fsd  f20, 32(sp)
  fsd  f21, 40(sp)
  fsd  f22, 48(sp)
  fsd  f23, 56(sp)
  fsd  f24, 64(sp)
  fsd  f25, 72(sp)
  fsd  f26, 80(sp)
  fsd  f27, 88(sp)

  # fcsr = 0x20 | (tag & 0xff)；tag 的取值保证 frm 是合法编码。
  andi t1, a0, 0xff
  ori  t1, t1, 0x20
  fscsr t1

#define SET_D(n)                    \
  slli t1, a0, 8;                   \
  ori  t1, t1, n;                   \
  li   t2, 0x3ff0000000000000;      \
  or   t1, t1, t2;                  \
  fmv.d.x f##n, t1;

#define SET_S(n)                    \
  slli t1, a0, 8;                   \
  ori  t1, t1, n;                   \
  li   t2, 0x3f000000;              \
  or   t1, t1, t2;                  \
  fmv.w.x f##n, t1;

  SET_D( 0)
  SET_S( 1)
  SET_D( 2)
  SET_S( 3)
  SET_D( 4)
  SET_S( 5)
  SET_D( 6)
  SET_S( 7)
  SET_D( 8)
  SET_S( 9)
  SET_D(10)
  SET_S(11)
  SET_D(12)
  SET_S(13)
  SET_D(14)
  SET_S(15)
  SET_D(16)
  SET_S(17)
  SET_D(18)
  SET_S(19)
  SET_D(20)
  SET_S(21)
  SET_D(22)
  SET_S(23)
  SET_D(24)
  SET_S(25)
  SET_D(26)
  SET_S(27)
  SET_D(28)
  SET_S(29)
  SET_D(30)
  SET_S(31)

  # 在浮点状态为脏值时停留一段时间，给时钟中断制造抢占窗口。
#ifndef PROBE_SPIN
#define PROBE_SPIN 200000
#endif
  li   t3, PROBE_SPIN
.Lprobe_delay:
  addi t3, t3, -1
  bnez t3, .Lprobe_delay

  # 和 AM yield() 相同的 ecall 约定：a7 == -1 表示主动让出。
  li   a7, -1
  ecall

#define CHK_D(n)                    \
  slli t1, a0, 8;                   \
  ori  t1, t1, n;                   \
  li   t2, 0x3ff0000000000000;      \
  or   t1, t1, t2;                  \
  fmv.x.d t0, f##n;                 \
  beq  t0, t1, .Lok_##n;            \
  li   a0, n + 1;                   \
  j    .Lprobe_fail;                \
.Lok_##n:

#define CHK_S(n)                    \
  slli t1, a0, 8;                   \
  ori  t1, t1, n;                   \
  li   t2, 0x3f000000;              \
  or   t1, t1, t2;                  \
  li   t2, 0xffffffff00000000;      \
  or   t1, t1, t2;                  \
  fmv.x.d t0, f##n;                 \
  beq  t0, t1, .Lok_##n;            \
  li   a0, n + 1;                   \
  j    .Lprobe_fail;                \
.Lok_##n:

  CHK_D( 0)
  CHK_S( 1)
  CHK_D( 2)
  CHK_S( 3)
  CHK_D( 4)
  CHK_S( 5)
  CHK_D( 6)
  CHK_S( 7)
  CHK_D( 8)
  CHK_S( 9)
  CHK_D(10)
  CHK_S(11)
  CHK_D(12)
  CHK_S(13)
  CHK_D(14)
  CHK_S(15)
  CHK_D(16)
  CHK_S(17)
  CHK_D(18)
  CHK_S(19)
  CHK_D(20)
  CHK_S(21)
  CHK_D(22)
  CHK_S(23)
  CHK_D(24)
  CHK_S(25)
  CHK_D(26)
  CHK_S(27)
  CHK_D(28)
  CHK_S(29)
  CHK_D(30)
  CHK_S(31)

  frcsr t0
  andi t0, t0, 0xff
  andi t1, a0, 0xff
  ori  t1, t1, 0x20
  beq  t0, t1, .Lprobe_fcsr_ok
  li   a0, 33
  j    .Lprobe_fail
.Lprobe_fcsr_ok:
  li   a0, 0

.Lprobe_fail:
  fld  f8,   0(sp)
  fld  f9,   8(sp)
  fld  f18, 16(sp)
  fld  f19, 24(sp)
  fld  f20, 32(sp)
  fld  f21, 40(sp)
  fld  f22, 48(sp)
  fld  f23, 56(sp)
  fld  f24, 64(sp)
  fld  f25, 72(sp)
  fld  f26, 80(sp)
  fld  f27, 88(sp)
  ld   t0, 96(sp)
  fscsr t0
  addi sp, sp, 112
  ret

  .size fp_probe, . - fp_probe
