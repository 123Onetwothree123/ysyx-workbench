/*
 * 默认（未开启 CONFIG_RISCV_C）构建的负向验证：
 * misa.C 必须为 0，并且 MXL 字段与 XLEN 一致。
 *
 * 该测试用不带 C 扩展的 -march=rv64ima / rv32ima 编译，跑在默认
 * riscv64_defconfig / riscv32_defconfig 构建上，确保新增的 C 扩展
 * 不会悄悄改变既有参考模型的 IALIGN=32 行为。
 */
#include "rvc-test.h"

.option arch, +zicsr
.option norvc

RVC_TEST_BEGIN

  csrr    t0, misa
  andi    t1, t0, 4
  bnez    t1, fail

#if __riscv_xlen == 64
  li      t1, 2
  slli    t1, t1, 62
#else
  li      t1, 1
  slli    t1, t1, 30
#endif
  and     t2, t0, t1
  bne     t2, t1, fail
  j       rvc_pass

RVC_TRAP_HANDLER
RVC_TEST_END
RVC_TEST_DATA
