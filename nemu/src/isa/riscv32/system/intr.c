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
  // 与 inst.c 保持一致：RV64 的 UXL/SXL 固定为 2。
  value = (value & ~(word_t)MSTATUS_XLEN_MASK) | MSTATUS_XLEN_64;
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
  const bool vectored_interrupt =
      (vector & 3u) == 1u && (cause & RISCV_INTR_MASK) != 0;
  return vectored_interrupt ? base + ((cause & RISCV_CAUSE_MASK) << 2) : base;
}

word_t isa_raise_intr(word_t NO, vaddr_t epc)
{
#ifdef CONFIG_ETRACE
  Log("etrace触发异常了, cause=%" PRIu64 ", epc=" FMT_WORD ", mtvec=" FMT_WORD ", stvec=" FMT_WORD,
      (uint64_t)NO, epc, cpu.mtvec, cpu.stvec);
#endif

  const bool from_su = cpu.privilege <= RISCV_PRIV_S;
  const bool is_interrupt = (NO & RISCV_INTR_MASK) != 0;
  const word_t cause = NO & RISCV_CAUSE_MASK;
  const word_t delegation = is_interrupt ? cpu.mideleg : cpu.medeleg;
  const bool delegated = from_su && cause < 32u &&
                         ((delegation >> cause) & 1u) != 0;

  if (delegated) {
    // S 模式异常：现场写入 scause/sepc/stval，并进入 stvec。
    if (is_interrupt) {
      isa_trap_tval = 0;
    }
    cpu.sepc = epc & ~(word_t)3u;
    cpu.scause = NO;
    cpu.stval = isa_trap_tval;
    cpu.mstatus = sstatus_on_trap(cpu.mstatus, cpu.privilege);
    cpu.privilege = RISCV_PRIV_S;
    return trap_vector(cpu.stvec, NO);
  }

  // M 模式异常：现场写入 mcause/mepc/mtval，并进入 mtvec。
  if (is_interrupt) {
    isa_trap_tval = 0;
  }
  cpu.mepc = epc & ~(word_t)3u;
  cpu.mcause = NO;
  cpu.mtval = isa_trap_tval;
  cpu.mstatus = mstatus_on_trap(cpu.mstatus, cpu.privilege);
  cpu.privilege = RISCV_PRIV_M;
  return trap_vector(cpu.mtvec, NO);
}

word_t isa_query_intr()
{
  // 时钟设备只提供一根 INTR 引脚，映射为 S 模式时钟中断挂起位。
  if (cpu.INTR) {
    cpu.INTR = false;
    cpu.mip |= (1u << 5); // STIP
  }

  // S 模式中断：SSIP/STIP/SEIP，必须同时满足委托、使能和全局开中断。
  const word_t s_pending = (1u << 1) | (1u << 5) | (1u << 9);
  if (cpu.privilege <= RISCV_PRIV_S && (cpu.mstatus & MSTATUS_SIE)) {
    const word_t enabled = cpu.mip & cpu.mie & cpu.mideleg & s_pending;
    for (int bit = 1; bit <= 9; bit += 4) {
      if (enabled & (1u << bit)) {
        cpu.mip &= ~(1u << bit);
        return RISCV_INTR_MASK | (word_t)bit;
      }
    }
  }

  // M 模式中断：MSIP/MTIP/MEIP。
  const word_t m_pending = (1u << 3) | (1u << 7) | (1u << 11);
  if (cpu.privilege == RISCV_PRIV_M && (cpu.mstatus & MSTATUS_MIE)) {
    const word_t enabled = cpu.mip & cpu.mie & m_pending;
    for (int bit = 3; bit <= 11; bit += 4) {
      if (enabled & (1u << bit)) {
        cpu.mip &= ~(1u << bit);
        return RISCV_INTR_MASK | (word_t)bit;
      }
    }
  }

  // 关中断期间保持挂起，等开中断后再响应。
  return INTR_EMPTY;
}
