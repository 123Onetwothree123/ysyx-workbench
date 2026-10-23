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

// newlib 的 sys/config.h 在 TARGET_AM 下也会定义 __EXPORT（空宏）。
// 只在本文件没有定义时补充可见性属性，避免 -Werror 下的宏重定义。
#ifndef __EXPORT
#define __EXPORT __attribute__((visibility("default")))
#endif
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

/*
 * 带 tval 的 trap 注入扩展（可选符号）。
 *
 * 旧的 difftest_raise_intr(no) 只能传递 cause，REF 会沿用自己上次的
 * tval，无法表达 DUT 的 mtval/stval。新接口把 no/epc/tval 作为三个
 * 固定 64 位参数传递，不改变寄存器快照（33/264 字节）和 CSR 快照
 * （4 x XLEN）的布局；RV32 REF 在内部自行截断到 32 位。
 *
 * 该符号是可选的：NPC 加载不到时降级为旧接口并在启动时明确告警；
 * 若 trap 的 tval 非零则拒绝继续比对，避免静默错跑。
 */
#define DIFFTEST_TRAP_ABI_VERSION 1U
#ifdef __cplusplus
extern "C" {
#endif
__EXPORT void difftest_trap(uint64_t no, uint64_t epc, uint64_t tval);

/*
 * 可选的失败诊断扩展。
 *
 * difftest_get_last_instruction() 返回 REF 最近一条实际执行的指令
 * （PC + 32 位指令字）。DUT 在 difftest_exec(1) 后读取，用于在首次
 * mismatch 时把 DUT/REF 指令并列打印；旧 REF 没有该符号时 DUT 必须
 * 明确降级为“REF 指令不可用”，不能影响原有逐退休比对。
 *
 * difftest_get_csr() 按 CSR 编号读取 REF 当前值，主要用于 trap 后
 * 检查 mtval/stval。找不到的定义（例如只读 CSR）返回 false。
 * 两个符号都是可选扩展，不改变既有的 33 x XLEN / 4 x XLEN 快照 ABI。
 */
__EXPORT void difftest_get_last_instruction(uint64_t *pc, uint64_t *inst);
__EXPORT bool difftest_get_csr(uint32_t csr, uint64_t *value);
#ifdef __cplusplus
}
#endif
#elif defined(CONFIG_ISA_loongarch32r)
# define DIFFTEST_REG_SIZE (sizeof(uint32_t) * 33) // GPRs + pc
#else
# error Unsupport ISA
#endif

#endif
