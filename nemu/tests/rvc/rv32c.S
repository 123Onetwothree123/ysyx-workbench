/*
 * RV32C 定向测试：RV32IMAC 下的混合 16/32 位取指、常用 C 指令、
 * C.JAL、C.LW/C.SW/C.LWSP/C.SWSP、JALR 低位语义和 CSR/trap 行为。
 *
 * 运行方式见 nemu/tests/rvc-smoke.sh。成功时 a0=0 并命中 NEMU trap。
 */
#include "rvc-test.h"

.option arch, +zicsr
.option rvc

RVC_TEST_BEGIN

  /* misa.C 与 RV32 的 MXL 字段必须同时正确。 */
  csrr    t0, misa
  andi    t1, t0, 4
  beqz    t1, fail
  li      t1, 1
  slli    t1, t1, 30
  and     t2, t0, t1
  bne     t2, t1, fail

  /* 16/32 位指令自由混排。 */
  .option norvc
  addi    s0, zero, 100
  addi    s0, s0, 1
  .option rvc
  c.addi  s0, -31
  li      t0, 70
  bne     s0, t0, fail

  c.nop
  c.li    s1, -31
  c.addi  s1, 31
  bnez    s1, fail
  c.li    s1, -1
  c.addi  s1, 1
  bnez    s1, fail

  /* C.LUI 的 6 位立即数符号扩展。 */
  lui     s2, 0xfffff
  li      t0, -4096
  bne     s2, t0, fail
  lui     s2, 0x1
  li      t0, 0x1000
  bne     s2, t0, fail

  /* C.ADDI4SPN / C.ADDI16SP。 */
  addi    sp, sp, -64
  c.addi16sp sp, 64
  c.addi16sp sp, -32
  c.addi16sp sp, 32
  c.addi4spn s0, sp, 32
  addi    t0, sp, 32
  bne     s0, t0, fail

  /* C.SLLI/C.SRLI/C.SRAI/C.ANDI。 */
  li      s0, 1
  c.slli  s0, 31
  li      t0, 1
  slli    t0, t0, 31
  bne     s0, t0, fail
  c.srli  s0, 31
  li      t0, 1
  bne     s0, t0, fail
  li      s1, -64
  c.srai  s1, 3
  li      t0, -8
  bne     s1, t0, fail
  c.andi  s1, 0x0f
  li      t0, 8
  bne     s1, t0, fail
  li      s1, 0x55
  c.andi  s1, -16
  li      t0, 0x50
  bne     s1, t0, fail

  /* C.SUB/C.XOR/C.OR/C.AND/C.MV/C.ADD。 */
  li      s0, 0x55
  li      s1, 0x0f
  c.sub   s0, s1
  li      t0, 0x46
  bne     s0, t0, fail
  c.xor   s0, s1
  li      t0, 0x49
  bne     s0, t0, fail
  c.or    s0, s1
  li      t0, 0x4f
  bne     s0, t0, fail
  c.and   s0, s1
  li      t0, 0x0f
  bne     s0, t0, fail
  c.mv    s2, s1
  c.add   s2, s1
  li      t0, 0x1e
  bne     s2, t0, fail

  /* C.LW/C.SW、C.LWSP/C.SWSP，以及 32 位 load 的符号扩展。 */
  la      a1, scratch
  li      a2, 0x12345678
  c.sw    a2, 0(a1)
  c.lw    a3, 0(a1)
  bne     a3, a2, fail
  li      a4, -2147483648
  c.sw    a4, 4(a1)
  c.lw    a5, 4(a1)
  bne     a5, a4, fail

  addi    sp, sp, -128
  c.swsp  a2, 8(sp)
  c.lwsp  a6, 8(sp)
  bne     a6, a2, fail
  addi    sp, sp, 128

  /* C.BEQZ/C.BNEZ、C.J 前向/后向跳转。 */
  li      s0, 0
  c.beqz  s0, 1f
  j       fail
1:
  li      s0, 1
  c.bnez  s0, 2f
  j       fail
2:
  li      s0, 3
3:
  c.addi  s0, -1
  c.bnez  s0, 3b
  bnez    s0, fail

  c.j     4f
  j       fail
4:
  c.j     5f
5:

  /* C.JAL（RV32 专有）与 C.JALR/C.JR。 */
  c.jal   func32
  li      t0, 1
  bne     s11, t0, fail
  la      t0, cfunc
  c.jalr  t0
  li      t0, 2
  bne     s11, t0, fail

  /* JALR 只清 bit0：目标地址为 label|1，label 位于 2 mod 4。 */
  la      t0, jalr_target
  ori     t0, t0, 1
  jalr    ra, 0(t0)
  li      t0, 3
  bne     s11, t0, fail

  /* C.EBREAK 与 ECALL 陷入。 */
  RVC_EXPECT 3, ebreak_site, 0, 2
ebreak_site:
  c.ebreak

  RVC_EXPECT 11, ecall_site, 0, 4
ecall_site:
  ecall

  /* mepc WARL：bit0 清零、bit1 在 RVC 下保留；mret 跳到 2 mod 4。 */
  la      t0, mepc_target
  ori     t0, t0, 1
  csrw    mepc, t0
  csrr    t1, mepc
  la      t2, mepc_target
  bne     t1, t2, fail
  li      t0, 0x1800
  csrs    mstatus, t0
  mret
  j       fail
  .balign 4
  .2byte  0x0001
mepc_target:
  c.addi  s11, 1
  li      t0, 4
  bne     s11, t0, fail

  /* sepc WARL 与 sret 低位语义；sret 后进入 S 模式，测试收尾。 */
  la      t0, sret_target
  ori     t0, t0, 1
  csrw    sepc, t0
  csrr    t1, sepc
  la      t2, sret_target
  bne     t1, t2, fail
  li      t0, 0x100
  csrs    mstatus, t0
  /* RV32 的 PMP 在 S 模式下默认拒绝未匹配访问；给测试配置一条
   * 全地址 NAPOT 表项（R/W/X），否则 sret 进入 S 模式后的取指会
   * 触发 access fault，测不到 sret 本身。 */
  li      t0, -1
  csrw    pmpaddr0, t0
  li      t0, 0x1f
  csrw    pmpcfg0, t0
  sret
  j       fail
  .balign 4
  .2byte  0x0001
sret_target:
  c.addi  s11, 1
  li      t0, 5
  bne     s11, t0, fail
  j       rvc_pass

RVC_TRAP_HANDLER
RVC_TEST_END

.balign 4
.globl func32
.type func32, @function
func32:
  addi    s11, s11, 1
  ret
.size func32, . - func32

.balign 4
.globl cfunc
.type cfunc, @function
cfunc:
  c.addi  s11, 1
  c.jr    ra
.size cfunc, . - cfunc

.balign 4
.2byte  0x0001
jalr_target:
  c.addi  s11, 1
  jr      ra

RVC_TEST_DATA

.section .data
.balign 8
scratch:
  .word 0, 0
