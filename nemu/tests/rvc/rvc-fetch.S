/*
 * RV32C/RV64C 跨页与跨内存边界取指测试。
 *
 * 重点：
 * 1. 32 位指令位于页尾（0x...ffc）；
 * 2. 32 位指令跨越页边界（0x...1ffe）；
 * 3. 16 位指令位于页尾（0x...2ffe）；
 * 4. 16 位指令恰好位于物理内存最后两个字节（0x87fffffe），
 *    旧的一律取 4 字节实现会在这里越界。
 *
 * 运行方式见 nemu/tests/rvc-smoke.sh。
 */
#include "rvc-test.h"

.option arch, +zicsr
.option rvc

.section .text
RVC_TEST_BEGIN

  li      s0, 0
  li      s1, 0
  li      s2, 0
  li      s3, 0
  j       cross32_start

back32:
  li      t0, 2
  bne     s0, t0, fail
  j       crosssplit_start

backsplit:
  li      t0, 2
  bne     s1, t0, fail
  j       cross16_start

back16:
  li      t0, 2
  bne     s2, t0, fail
  j       memend_test

memend_test:
  /* 在物理内存最后两个字节写入 c.jr t0，然后跳过去执行。
   * 0x8282 = c.jr t0，t0 保存返回标签。 */
  la      t0, memend_back
  li      t2, 0x87fffffe
  li      t3, 0x8282
  sh      t3, 0(t2)
  li      s3, 1
  jalr    x0, 0(t2)

memend_back:
  li      t0, 1
  bne     s3, t0, fail
  j       memfault_test

memfault_test:
  /* 在最后两个字节写入 32 位指令的低半字（低两位为 11），高半字
   * 位于 0x88000000，已经越过物理内存。必须产生指令访问异常
   * （cause 1），而不是 paddr_read 越界 panic。 */
  li      t2, 0x87fffffe
  li      t3, 0x0013
  sh      t3, 0(t2)
  RVC_EXPECT_ABS 1, 0x87fffffe, 0x88000000, 0
  /* 返回到 memfault_back：skip 是链接期才能确定的负偏移，单独计算。 */
  la      t0, memfault_back
  li      t1, 0x87fffffe
  sub     t0, t0, t1
  la      t1, rvc_exp_skip
  RVC_ST  t0, 0(t1)
  jalr    x0, 0(t2)

memfault_back:
  j       rvc_pass

RVC_TRAP_HANDLER
RVC_TEST_END

/* 32 位指令恰好结束在 0x80000ffc..0x80000fff。 */
.section .cross32, "ax"
.option norvc
cross32_start:
  addi    s0, s0, 1

.section .cross32_next, "ax"
  addi    s0, s0, 1
  jal     x0, back32

/* 32 位指令从 0x80001ffe 开始，跨越到下一页 0x80002000。 */
.section .crosssplit, "ax"
crosssplit_start:
  addi    s1, s1, 1
  addi    s1, s1, 1
  jal     x0, backsplit

/* 16 位指令位于 0x80002ffe，下一条从 0x80003000 继续。 */
.section .cross16, "ax"
.option rvc
cross16_start:
  c.addi  s2, 1

.section .cross16_next, "ax"
  c.addi  s2, 1
  jal     x0, back16

RVC_TEST_DATA
