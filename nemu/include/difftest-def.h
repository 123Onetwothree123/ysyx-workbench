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

#ifndef __DIFFTEST_DEF_H__
#define __DIFFTEST_DEF_H__

#include <stdint.h>
#include <macro.h>
#include <generated/autoconf.h>

#define __EXPORT __attribute__((visibility("default")))
enum { DIFFTEST_TO_DUT, DIFFTEST_TO_REF };

#if defined(CONFIG_ISA_x86)
# define DIFFTEST_REG_SIZE (sizeof(uint32_t) * 9) // GPRs + pc
#elif defined(CONFIG_ISA_mips32)
# define DIFFTEST_REG_SIZE (sizeof(uint32_t) * 38) // GPRs + status + lo + hi + badvaddr + cause + pc
#elif defined(CONFIG_ISA_riscv)
#define RISCV_GPR_TYPE MUXDEF(CONFIG_RV64, uint64_t, uint32_t)
/*
 * The external RISC-V DiffTest ABI is deliberately independent of the
 * implementation's private CPU_state.  In particular, an RV32E NEMU still
 * exchanges all 32 architectural slots (x16..x31 are zero).  Keeping the
 * buffer fixed prevents a 132-byte REF copy from overflowing an RV32E
 * CPU_state, whose GPR array contains only 16 entries.
 */
typedef struct {
  RISCV_GPR_TYPE gpr[32];
  RISCV_GPR_TYPE pc;
} riscv_difftest_state_t;
#define DIFFTEST_REG_SIZE (sizeof(riscv_difftest_state_t))
typedef struct {
  RISCV_GPR_TYPE mstatus;
  RISCV_GPR_TYPE mtvec;
  RISCV_GPR_TYPE mepc;
  RISCV_GPR_TYPE mcause;
} riscv_difftest_csr_state_t;
#elif defined(CONFIG_ISA_loongarch32r)
# define DIFFTEST_REG_SIZE (sizeof(uint32_t) * 33) // GPRs + pc
#else
# error Unsupport ISA
#endif

#endif
