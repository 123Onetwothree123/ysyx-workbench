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
#ifdef CONFIG_ISA_riscv
_Static_assert(sizeof(riscv_difftest_state_t) == 33 * sizeof(RISCV_GPR_TYPE),
               "unexpected RISC-V DiffTest ABI padding");
_Static_assert(sizeof(riscv_difftest_csr_state_t) == 4 * sizeof(RISCV_GPR_TYPE),
               "unexpected RISC-V DiffTest CSR ABI padding");
#endif

__EXPORT size_t difftest_state_size(void)
{
  return sizeof(riscv_difftest_state_t);
}

__EXPORT size_t difftest_csr_state_size(void)
{
  return sizeof(riscv_difftest_csr_state_t);
}

#ifdef CONFIG_RISCV_F
_Static_assert(sizeof(riscv_difftest_fp_state_t) == 33 * sizeof(uint64_t),
               "unexpected RISC-V DiffTest FP ABI padding");

/*
 * 可选浮点状态 ABI。未打开 F/D 的 REF 不导出这些符号，旧 DUT 也
 * 不会尝试 dlsym，因此整数 DiffTest 的 33 x XLEN 布局完全不变。
 */
__EXPORT uint32_t difftest_fp_abi_version(void)
{
  return DIFFTEST_FP_ABI_VERSION;
}

__EXPORT size_t difftest_fp_state_size(void)
{
  return sizeof(riscv_difftest_fp_state_t);
}

__EXPORT void difftest_fp_regcpy(void *dut, bool direction)
{
  assert(dut != NULL);
  riscv_difftest_fp_state_t *state = (riscv_difftest_fp_state_t *)dut;
  if (direction == DIFFTEST_TO_REF)
  {
    for (int i = 0; i < 32; i++)
    {
      cpu.f[i] = state->freg[i];
    }
    cpu.fcsr = (word_t)(state->fcsr & 0xffu);
  }
  else
  {
    for (int i = 0; i < 32; i++)
    {
      state->freg[i] = cpu.f[i];
    }
    state->fcsr = (uint64_t)(cpu.fcsr & 0xffu);
  }
}
#endif

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

/*
 * 可选诊断接口：按 CSR 编号读取 REF 当前值。失败报告用它确认 trap 后
 * tval 实际落在 mtval 还是 stval，以及 64 位值没有被截断。
 *
 * 这里只暴露不会改变架构状态的普通读取；未实现/只读编号返回 false，
 * 调用方据此降级，而不是把 0 当成真实 CSR 值。
 */
__EXPORT bool difftest_get_csr(uint32_t csr, uint64_t *value)
{
  if (value == NULL)
  {
    return false;
  }

  word_t result = 0;
  switch (csr)
  {
#ifdef CONFIG_RISCV_F
  /* 浮点 CSR 便于失败诊断时核对 fflags/frm，不改变 REF 状态。 */
  case 0x001: result = cpu.fcsr & 0x1fu; break;
  case 0x002: result = BITS(cpu.fcsr, 7, 5); break;
  case 0x003: result = cpu.fcsr & 0xffu; break;
#endif
  case 0x100: result = cpu.mstatus & SSTATUS_MASK; break;
  case 0x104: result = cpu.mie; break;
  case 0x105: result = cpu.stvec; break;
  case 0x106: result = cpu.scounteren; break;
  case 0x140: result = cpu.sscratch; break;
  case 0x141: result = cpu.sepc; break;
  case 0x142: result = cpu.scause; break;
  case 0x143: result = cpu.stval; break;
  case 0x144: result = cpu.mip; break;
  case 0x180: result = cpu.satp; break;
  case 0x300: result = cpu.mstatus; break;
  case 0x302: result = cpu.medeleg; break;
  case 0x303: result = cpu.mideleg; break;
  case 0x304: result = cpu.mie; break;
  case 0x305: result = cpu.mtvec; break;
  case 0x306: result = cpu.mcounteren; break;
  case 0x320: result = cpu.mcountinhibit; break;
  case 0x340: result = cpu.mscratch; break;
  case 0x341: result = cpu.mepc; break;
  case 0x342: result = cpu.mcause; break;
  case 0x343: result = cpu.mtval; break;
  case 0x344: result = cpu.mip; break;
  default: return false;
  }

  /* word_t 跟随 XLEN；RV32 高 32 位自然为 0，RV64 保留完整 64 位。 */
  *value = (uint64_t)result;
  return true;
}

__EXPORT void difftest_exec(uint64_t n)
{
  // assert(0);
  cpu_exec(n);
}

/*
 * 旧 ABI：只注入 cause，没有 tval。这里显式把暂存 tval 清零，避免
 * 上一次异常遗留的 isa_trap_tval 污染本次 mtval/stval。需要真实 tval
 * 的 DUT 应改用 difftest_trap()。
 */
__EXPORT void difftest_raise_intr(uint64_t NO)
{
  isa_trap_tval = 0;
  cpu.pc = isa_raise_intr((word_t)NO, cpu.pc);
}

/*
 * 带 tval 的 trap 注入：先建立 epc 和 tval，再由 isa_raise_intr() 一次性
 * 写入 cause/epc/tval 和 MPP，保证三者对应同一个架构边界。tval 在
 * isa_raise_intr() 内部按委托目标写入 mtval 或 stval；RV32 REF 在此处
 * 自然截断为 32 位。
 */
__EXPORT void difftest_trap(uint64_t no, uint64_t epc, uint64_t tval)
{
  cpu.pc = (word_t)epc;
  isa_trap_tval = (word_t)tval;
  cpu.pc = isa_raise_intr((word_t)no, (vaddr_t)epc);
  /* 已落入 mtval/stval；清空暂存，后续旧接口调用不会复用本次 tval。 */
  isa_trap_tval = 0;
}

__EXPORT void difftest_init(int port)
{
  void init_mem();
  init_mem();
  /* Perform ISA dependent initialization. */
  init_isa();
}
