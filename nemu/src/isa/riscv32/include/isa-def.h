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

  // PMP：16 个表项。RV32 的 pmpcfg0~3 每个装 4 项；
  // RV64 的 pmpcfg0/pmpcfg2 每个装 8 项，pmpcfg1/3 不存在。
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
/*
 * RV64 的 mstatus 在 bit[35:32] 还包含 UXL/SXL。NEMU 只实现 64 位用户态
 * 和监管态，因此这两位是 WARL 并固定为 2（XLEN=64）。这些位必须纳入
 * 可写掩码，否则软件写回 mstatus 时会把它们意外清零。
 */
#ifdef CONFIG_RV64
#define MSTATUS_UXL_MASK ((word_t)3 << 32)
#define MSTATUS_SXL_MASK ((word_t)3 << 34)
#define MSTATUS_XLEN_MASK (MSTATUS_UXL_MASK | MSTATUS_SXL_MASK)
#define MSTATUS_XLEN_64   ((word_t)2 << 32 | (word_t)2 << 34)
#else
#define MSTATUS_XLEN_MASK 0
#define MSTATUS_XLEN_64   0
#endif
#define MSTATUS_WRITABLE_MASK \
  (MSTATUS_SIE | MSTATUS_MIE | MSTATUS_SPIE | MSTATUS_MPIE | MSTATUS_SPP | \
   MSTATUS_MPP_MASK | MSTATUS_MPRV | MSTATUS_SUM | MSTATUS_MXR | \
   MSTATUS_TVM | MSTATUS_TW | MSTATUS_TSR | MSTATUS_XLEN_MASK)
#define SSTATUS_MASK \
  (MSTATUS_SIE | MSTATUS_SPIE | MSTATUS_SPP | MSTATUS_SUM | MSTATUS_MXR)
#define SSTATUS_WRITABLE_MASK SSTATUS_MASK

// 中断位位于 XLEN-1：RV32 为 bit31，RV64 为 bit63。
#define RISCV_INTR_BIT MUXDEF(CONFIG_RV64, 63, 31)
#define RISCV_INTR_MASK ((word_t)1 << RISCV_INTR_BIT)
#define RISCV_CAUSE_MASK (RISCV_INTR_MASK - 1)

// RV64 页表项为 8 字节，satp.MODE 位于 bit[63:60]。
#define RISCV_PTE_SIZE MUXDEF(CONFIG_RV64, 8, 4)
#define RISCV_SATP_MODE_SHIFT MUXDEF(CONFIG_RV64, 60, 31)
#define RISCV_SATP_ASID_SHIFT MUXDEF(CONFIG_RV64, 44, 22)
#define RISCV_SATP_PPN_BITS MUXDEF(CONFIG_RV64, 44, 22)

// RV64 PMP 配置寄存器每个装 8 个表项，RV32 每个装 4 个表项。
// RV64 实际存在的 CSR 是 pmpcfg0/pmpcfg2，因此表项 8~15 对应数组下标 2。
#define PMP_CFG_ENTRIES_PER_REG MUXDEF(CONFIG_RV64, 8, 4)
#define PMP_CFG_ENTRY_SHIFT(i) (((i) % PMP_CFG_ENTRIES_PER_REG) * 8)
#define PMP_CFG_REG_INDEX(i) \
  MUXDEF(CONFIG_RV64, (((i) < 8) ? 0 : 2), ((i) / PMP_CFG_ENTRIES_PER_REG))

// decode
typedef struct
{
  uint32_t inst;
} MUXDEF(CONFIG_RV64, riscv64_ISADecodeInfo, riscv32_ISADecodeInfo);

// 分页规则在 mmu.c 的 isa_mmu_check() 中实现：
// RV32 使用 Sv32（satp.MODE=1，4B PTE，两级页表）；
// RV64 使用 Sv39（MODE=8，8B PTE，三级页表）或 Sv48（MODE=9，四级页表）。
// S/U 模式在分页开启时进行地址转换；M 模式默认直接访问物理地址，
// 只有 MPRV=1 的数据访问才按 MPP 指定的特权级进行转换。

#endif
