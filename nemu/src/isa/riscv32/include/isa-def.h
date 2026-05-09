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

#ifndef __ISA_RISCV_H__
#define __ISA_RISCV_H__

#include <common.h>

typedef struct
{
  word_t gpr[MUXDEF(CONFIG_RVE, 16, 32)];
  vaddr_t pc;

  /*
  riscv32
riscv32提供ecall指令作为自陷指令, 并提供一个mtvec寄存器来存放异常入口地址. 为了保存程序当前的状态, riscv32提供了一些特殊的系统寄存器, 叫控制状态寄存器(CSR寄存器). 在PA中, 我们只使用如下3个CSR寄存器:

mepc寄存器 - 存放触发异常的PC
mstatus寄存器 - 存放处理器的状态
mcause寄存器 - 存放触发异常的原因
https://ysyx.oscc.cc/docs/ics-pa/3.2.html
  */
  vaddr_t mepc;
  word_t mcause;
  /*
  让DiffTest支持异常响应机制
 为了让DiffTest机制正确工作, 你需要

 针对x86:
 NEMU中不实现分段机制, 没有cs寄存器的概念. 但为了顺利进行DiffTest, 你还是需要在cpu结构体中添加一个cs寄存器, 并在将其初始化为8.
 由于x86的异常响应机制需要对eflags进行压栈, 你还需要将eflags初始化为0x2.
 针对riscv32, 你需要将mstatus初始化为0x1800.
 针对riscv64, 你需要将mstatus初始化为0xa00001800.
  */
  word_t mstatus;
  vaddr_t mtvec; // 存放异常入口地址的
} MUXDEF(CONFIG_RV64, riscv64_CPU_state, riscv32_CPU_state);

// decode
typedef struct
{
  uint32_t inst;
} MUXDEF(CONFIG_RV64, riscv64_ISADecodeInfo, riscv32_ISADecodeInfo);

#define isa_mmu_check(vaddr, len, type) (MMU_DIRECT)

#endif
