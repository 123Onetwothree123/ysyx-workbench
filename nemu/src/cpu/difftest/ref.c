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
#include <cpu/cpu.h>
#include <difftest-def.h>
#include <memory/paddr.h>

/*
 * NPC <-> NEMU register-copy ABI.
 *
 * Do not memcpy CPU_state here: that private structure contains CSRs and its
 * GPR array changes size under CONFIG_RVE.  The caller cannot know either
 * layout, which previously made a normal RV32 copy overrun a 132-byte NPC
 * buffer by 16 bytes.  Keep the shared ABI fixed at 32 RV32 GPRs plus PC and
 * translate explicitly at this boundary.
 */
_Static_assert(sizeof(riscv_difftest_state_t) == 33 * sizeof(RISCV_GPR_TYPE),
               "unexpected RISC-V DiffTest ABI padding");

__EXPORT size_t difftest_state_size(void)
{
  return sizeof(riscv_difftest_state_t);
}

__EXPORT size_t difftest_csr_state_size(void)
{
  return sizeof(riscv_difftest_csr_state_t);
}

__EXPORT void difftest_set_platform(int ysyxsoc)
{
  set_ysyxsoc_memory_mode(ysyxsoc != 0);
}

__EXPORT bool difftest_memory_range_supported(paddr_t addr, size_t n)
{
  return in_backed_memory_range(addr, n);
}

__EXPORT void difftest_memcpy(paddr_t addr, void *buf, size_t n, bool direction)
{
  // assert(0);
  if (n == 0)
  {
    return;
  }
  assert(buf != NULL);
  /* Validate the complete copy, not just its first byte.  The SoC image lives
   * in the 0x30000000 flash/MROM window and must be accepted in SoC mode. */
  assert(in_backed_memory_range(addr, n));
  if (direction == DIFFTEST_TO_REF)
  {
    memcpy(guest_to_host(addr), buf, n);
  }
  else
  {
    memcpy(buf, guest_to_host(addr), n);
  }
}

__EXPORT void difftest_regcpy(void *dut, bool direction)
{
  assert(dut != NULL);
  riscv_difftest_state_t *state =
      (riscv_difftest_state_t *)dut;
  const size_t nemu_gpr_count = sizeof(cpu.gpr) / sizeof(cpu.gpr[0]);
  if (direction == DIFFTEST_TO_REF)
  {
    for (size_t i = 0; i < nemu_gpr_count; ++i)
    {
      cpu.gpr[i] = state->gpr[i];
    }
    cpu.pc = state->pc;
  }
  else
  {
    memset(state, 0, sizeof(*state));
    for (size_t i = 0; i < nemu_gpr_count; ++i)
    {
      state->gpr[i] = cpu.gpr[i];
    }
    state->pc = cpu.pc;
  }
}

__EXPORT void difftest_csrcpy(void *dut, bool direction)
{
  assert(dut != NULL);
  riscv_difftest_csr_state_t *state =
      (riscv_difftest_csr_state_t *)dut;
  if (direction == DIFFTEST_TO_REF)
  {
    cpu.mstatus = state->mstatus;
    cpu.mtvec = state->mtvec;
    cpu.mepc = state->mepc;
    cpu.mcause = state->mcause;
  }
  else
  {
    state->mstatus = cpu.mstatus;
    state->mtvec = cpu.mtvec;
    state->mepc = cpu.mepc;
    state->mcause = cpu.mcause;
  }
}

__EXPORT void difftest_exec(uint64_t n)
{
  // assert(0);
  cpu_exec(n);
}

__EXPORT void difftest_raise_intr(uint64_t NO)
{
  cpu.pc = isa_raise_intr((word_t)NO, cpu.pc);
}

__EXPORT void difftest_init(int port)
{
  void init_mem();
  init_mem();
  /* Perform ISA dependent initialization. */
  init_isa();
}
