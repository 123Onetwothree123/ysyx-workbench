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

static inline word_t sstatus_on_trap(word_t old, word_t privilege)
{
  const word_t sie = BITS(old, 1, 1);
  /* SPP 记录陷入 S 模式前的特权级；SPIE 记录旧的 SIE。 */
  word_t next = old & ~(word_t)(MSTATUS_SPP | MSTATUS_SPIE | MSTATUS_SIE);
  next |= ((privilege == RISCV_PRIV_S) ? MSTATUS_SPP : 0u) |
          (sie ? MSTATUS_SPIE : 0u);
  return mstatus_warl(next);
}

static word_t trap_vector(word_t vector, word_t cause)
{
  const word_t base = vector & ~(word_t)3u;
  const bool vectored_interrupt = (vector & 3u) == 1u && BITS(cause, 31, 31);
  return vectored_interrupt ? base + ((cause & 0x7fffffffu) << 2) : base;
}

word_t isa_raise_intr(word_t NO, vaddr_t epc)
{
#ifdef CONFIG_ETRACE
  Log("etrace触发异常了, cause=%d, epc=" FMT_WORD ", mtvec=" FMT_WORD ", stvec=" FMT_WORD,
      NO, epc, cpu.mtvec, cpu.stvec);
#endif

  const bool from_su = cpu.privilege <= RISCV_PRIV_S;
  const bool delegated = from_su && NO < 32u &&
                         ((cpu.medeleg >> NO) & 1u) != 0;

  if (delegated) {
    // S 模式异常：现场写入 scause/sepc/stval，并进入 stvec。
    cpu.sepc = epc & ~(word_t)3u;
    cpu.scause = NO;
    cpu.stval = 0;
    cpu.mstatus = sstatus_on_trap(cpu.mstatus, cpu.privilege);
    cpu.privilege = RISCV_PRIV_S;
    return trap_vector(cpu.stvec, NO);
  }

  // M 模式异常：现场写入 mcause/mepc/mtval，并进入 mtvec。
  cpu.mepc = epc & ~(word_t)3u;
  cpu.mcause = NO;
  cpu.mtval = 0;
  cpu.mstatus = mstatus_on_trap(cpu.mstatus, cpu.privilege);
  cpu.privilege = RISCV_PRIV_M;
  return trap_vector(cpu.mtvec, NO);
}

word_t isa_query_intr()
{
  return INTR_EMPTY;
}
