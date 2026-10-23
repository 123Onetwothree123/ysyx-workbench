/*
 * RV32C/RV64C 非法编码定向测试。
 *
 * 每条非法指令都通过通用 trap handler 核对：
 *   mcause = 2（非法指令）
 *   mepc   = 该 16/32 位指令的起始地址
 *   mtval  = 原始 16/32 位编码（不能被截断/拼接成别的指令）
 * 然后按真实长度跳过，继续执行下一条。这样既验证不会把非法 16 位
 * 编码当成 32 位指令执行，也验证 trap 现场的低位语义。
 */
#include "rvc-test.h"

.option arch, +zicsr
.option rvc

RVC_TEST_BEGIN

  /* 放在 2 mod 4 的非法 C 编码：mepc 必须保留 bit1。 */
  .balign 4
  c.nop
  RVC_EXPECT 2, ill_addi4spn, 0x0000, 2
ill_addi4spn:
  .2byte  0x0000              /* C.ADDI4SPN nzuimm=0 */

  RVC_EXPECT 2, ill_addi16sp, 0x6101, 2
ill_addi16sp:
  .2byte  0x6101              /* C.ADDI16SP nzimm=0 */

  RVC_EXPECT 2, ill_lui, 0x6181, 2
ill_lui:
  .2byte  0x6181              /* C.LUI imm=0 */

  RVC_EXPECT 2, ill_lwsp, 0x4002, 2
ill_lwsp:
  .2byte  0x4002              /* C.LWSP rd=0 */

  RVC_EXPECT 2, ill_jr, 0x8002, 2
ill_jr:
  .2byte  0x8002              /* C.JR rs1=0 */

  RVC_EXPECT 2, ill_q0_100, 0x8000, 2
ill_q0_100:
  .2byte  0x8000              /* Quadrant 0 funct3=100 保留 */

  RVC_EXPECT 2, ill_fldsp, 0x2002, 2
ill_fldsp:
  .2byte  0x2002              /* C.FLDSP：未实现 D 扩展 */

  RVC_EXPECT 2, ill_fld, 0x2000, 2
ill_fld:
  .2byte  0x2000              /* C.FLD：未实现 D 扩展 */

  RVC_EXPECT 2, ill_fsd, 0xa000, 2
ill_fsd:
  .2byte  0xa000              /* C.FSD：未实现 D 扩展 */

#if __riscv_xlen == 32
  RVC_EXPECT 2, ill_flw, 0x6000, 2
ill_flw:
  .2byte  0x6000              /* RV32 C.FLW：未实现 F 扩展 */

  RVC_EXPECT 2, ill_fsw, 0xe000, 2
ill_fsw:
  .2byte  0xe000              /* RV32 C.FSW：未实现 F 扩展 */

  RVC_EXPECT 2, ill_srli, 0x9001, 2
ill_srli:
  .2byte  0x9001              /* RV32 C.SRLI shamt[5]=1 保留 */

  RVC_EXPECT 2, ill_srai, 0x9401, 2
ill_srai:
  .2byte  0x9401              /* RV32 C.SRAI shamt[5]=1 保留 */

  RVC_EXPECT 2, ill_slli, 0x1012, 2
ill_slli:
  .2byte  0x1012              /* RV32 C.SLLI shamt[5]=1 保留 */

  RVC_EXPECT 2, ill_subw32, 0x9c01, 2
ill_subw32:
  .2byte  0x9c01              /* RV32 没有 C.SUBW */

  RVC_EXPECT 2, ill_addw32, 0x9c21, 2
ill_addw32:
  .2byte  0x9c21              /* RV32 没有 C.ADDW */
#else
  RVC_EXPECT 2, ill_addiw, 0x2001, 2
ill_addiw:
  .2byte  0x2001              /* RV64 C.ADDIW rd=0 保留 */

  RVC_EXPECT 2, ill_subw_rsv, 0x9c41, 2
ill_subw_rsv:
  .2byte  0x9c41              /* RV64 C.SUBW 组中 funct=10 保留 */

  RVC_EXPECT 2, ill_addw_rsv, 0x9c61, 2
ill_addw_rsv:
  .2byte  0x9c61              /* RV64 C.ADDW 组中 funct=11 保留 */
#endif

  /* 32 位非法编码必须按完整 32 位写 mtval，不能只报低半字。 */
  RVC_EXPECT 2, ill_word, 0xffffffff, 4
ill_word:
  .word   0xffffffff

  /* 非法指令之后继续执行，确认 trap 返回路径正常。 */
  li      t0, 0x1234
  li      t1, 0x1234
  bne     t0, t1, fail
  j       rvc_pass

RVC_TRAP_HANDLER
RVC_TEST_END
RVC_TEST_DATA
