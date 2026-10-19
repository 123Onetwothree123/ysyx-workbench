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
  vaddr_t mtval;

  // S 模式异常现场
  vaddr_t sepc;
  word_t scause;
  vaddr_t stvec;
  word_t sscratch;
  word_t stval;

  // 异常/中断委托寄存器
  word_t medeleg;
  word_t mideleg;
  word_t mie;
  word_t mip;

  // PMP：RV32 支持 16 个表项
  word_t pmpcfg[4];
  word_t pmpaddr[16];

  word_t mcounteren;
  word_t scounteren;
  word_t mcountinhibit;
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
  word_t mscratch; // 陷入时存放当前内核栈顶，并暂存陷入前的 sp
  word_t satp;   // Sv32 页目录基地址与分页使能位（bit 31 为 MODE）
  word_t privilege; // 当前特权级：0 = U, 1 = S, 3 = M
  bool INTR;        // 外部中断请求引脚，目前由时钟中断拉高
} MUXDEF(CONFIG_RV64, riscv64_CPU_state, riscv32_CPU_state);

#define RISCV_PRIV_U 0u
#define RISCV_PRIV_S 1u
#define RISCV_PRIV_M 3u

#define MSTATUS_SIE       (1u << 1)
#define MSTATUS_MIE       (1u << 3)
#define MSTATUS_SPIE      (1u << 5)
#define MSTATUS_MPIE      (1u << 7)
#define MSTATUS_SPP       (1u << 8)
#define MSTATUS_MPP_SHIFT 11
#define MSTATUS_MPP_MASK  (3u << MSTATUS_MPP_SHIFT)
#define MSTATUS_MPRV      (1u << 17)
#define MSTATUS_SUM       (1u << 18)
#define MSTATUS_MXR       (1u << 19)
#define MSTATUS_TVM       (1u << 20)
#define MSTATUS_TW        (1u << 21)
#define MSTATUS_TSR       (1u << 22)
#define MSTATUS_WRITABLE_MASK \
  (MSTATUS_SIE | MSTATUS_MIE | MSTATUS_SPIE | MSTATUS_MPIE | MSTATUS_SPP | \
   MSTATUS_MPP_MASK | MSTATUS_MPRV | MSTATUS_SUM | MSTATUS_MXR | \
   MSTATUS_TVM | MSTATUS_TW | MSTATUS_TSR)
#define SSTATUS_MASK \
  (MSTATUS_SIE | MSTATUS_SPIE | MSTATUS_SPP | MSTATUS_SUM | MSTATUS_MXR)
#define SSTATUS_WRITABLE_MASK SSTATUS_MASK

// decode
typedef struct
{
  uint32_t inst;
} MUXDEF(CONFIG_RV64, riscv64_ISADecodeInfo, riscv32_ISADecodeInfo);

// 分页规则在 mmu.c 的 isa_mmu_check() 中实现：
// S/U 模式在 satp.MODE=1 时进行地址转换；M 模式默认直接访问物理地址，
// 只有 MPRV=1 的数据访问才按 MPP 指定的特权级进行转换。

#endif
