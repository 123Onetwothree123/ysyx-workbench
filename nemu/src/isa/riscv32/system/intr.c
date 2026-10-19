/***************************************************************************************
 * Copyright (c) 2014-2024 Zihao Yu, Nanjing University
 *
 * NEMU is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *          http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 *
 * See the Mulan PSL v2 for more details.
 ***************************************************************************************/

#include <isa.h>

static inline word_t mstatus_warl(word_t value)
{
  return value & (word_t)MSTATUS_WRITABLE_MASK;
}

static inline word_t mstatus_on_trap(word_t old, word_t privilege)
{
  const word_t mie = BITS(old, 3, 3);
  /* MPP 记录进入异常前的特权级；MPIE 记录旧的 MIE。 */
  word_t next = old & ~(word_t)(MSTATUS_MPP_MASK | MSTATUS_MPIE | MSTATUS_MIE);
  next |= ((privilege & 3u) << MSTATUS_MPP_SHIFT) | (mie ? MSTATUS_MPIE : 0u);
  return mstatus_warl(next);
}

word_t isa_raise_intr(word_t NO, vaddr_t epc)
{
  /* TODO: Trigger an interrupt/exception with ``NO''.
   * Then return the address of the interrupt/exception vector.
   */
  // return 0;
#ifdef CONFIG_ETRACE
  Log("etrace触发异常了, mcause=%d, mepc=" FMT_WORD ", mtvec=" FMT_WORD, NO, epc, cpu.mtvec);
#endif
  /* The core implements IALIGN=32, so mepc[1:0] are WARL zero. */
  cpu.mepc = epc & ~(word_t)3u;
  cpu.mcause = NO;
  cpu.mstatus = mstatus_on_trap(cpu.mstatus, cpu.privilege);
  cpu.privilege = RISCV_PRIV_M; // 所有异常都在 M 模式处理

  const word_t base = cpu.mtvec & ~(word_t)3u;
  const bool vectored_interrupt = (cpu.mtvec & 3u) == 1u && BITS(NO, 31, 31);
  return vectored_interrupt ? base + ((NO & 0x7fffffffu) << 2) : base;
}

word_t isa_query_intr()
{
  return INTR_EMPTY;
}
