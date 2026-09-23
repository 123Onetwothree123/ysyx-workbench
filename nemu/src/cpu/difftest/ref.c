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
typedef struct
{
  uint32_t gpr[32];
  uint32_t pc;
} riscv32_difftest_state_t;

_Static_assert(sizeof(riscv32_difftest_state_t) == 33 * sizeof(uint32_t),
               "unexpected RV32 DiffTest ABI padding");

__EXPORT size_t difftest_state_size(void)
{
  return sizeof(riscv32_difftest_state_t);
}

__EXPORT void difftest_memcpy(paddr_t addr, void *buf, size_t n, bool direction)
{
  // assert(0);
  if (n == 0)
  {
    return;
  }
  assert(buf != NULL);
  // assert(in_pmem_range(addr, n));
  // 适配新版difftest
  assert(in_pmem_range(addr, n) || in_mrom(addr) || in_sram(addr));
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
  riscv32_difftest_state_t *state =
      (riscv32_difftest_state_t *)dut;
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
