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
#include <memory/paddr.h>

#ifdef CONFIG_RV64
_Static_assert(sizeof(word_t) == 8, "RV64 模式下 word_t 必须是 64 位");
/*
 * RV64 内置自测镜像：
 *   auipc t0, 0          t0 = 0x80000000
 *   auipc t1, 1          t1 = 0x80001000
 *   slli  t0, t0, 1      t0 = 0x0000000100000000（RV32 会截断成 0）
 *   sd    t0, 0(t1)      验证 64 位 store
 *   ld    t2, 0(t1)      验证 64 位 load
 *   srli  a0, t0, 32     a0 = 1
 *   addi  a0, a0, -1     a0 = 0，作为 GOOD TRAP 的退出码
 *   custom-0             nemu_trap
 */
static const uint32_t img[] = {
    0x00000297, // auipc t0,0
    0x00001317, // auipc t1,1
    0x00129293, // slli t0,t0,1
    0x00533023, // sd t0,0(t1)
    0x00033383, // ld t2,0(t1)
    0x0202d513, // srli a0,t0,32
    0xfff50513, // addi a0,a0,-1
    0x0000000b, // custom-0 (used as nemu_trap)
};
#else
static const uint32_t img[] = {
    0x00000297, // auipc t0,0
    0x00028823, // sb  zero,16(t0)
    0x0102c503, // lbu a0,16(t0)
    0x0000000b, // custom-0 (used as nemu_trap)
    0xdeadbeef, // some data
};
#endif

static void restart()
{
  /* Set the initial program counter. */
  cpu.pc = CONFIG_MBASE;
  cpu.gpr[0] = 0;
  /* Match the RTL reset state.  DiffTest must not begin with an invisible
   * mstatus mismatch and wait until a later CSR read to discover it. */
  cpu.mstatus = MUXDEF(CONFIG_RV64, 0xa00001800ull, 0x1800u);
  cpu.mtvec = 0;
  cpu.mscratch = 0;
  cpu.mepc = 0;
  cpu.mcause = 0;
  cpu.mtval = 0;
  cpu.sepc = 0;
  cpu.scause = 0;
  cpu.stvec = 0;
  cpu.sscratch = 0;
  cpu.stval = 0;
  cpu.medeleg = 0;
  cpu.mideleg = 0;
  cpu.mie = 0;
  cpu.mip = 0;
  memset(cpu.pmpcfg, 0, sizeof(cpu.pmpcfg));
  memset(cpu.pmpaddr, 0, sizeof(cpu.pmpaddr));
  cpu.mcounteren = 0;
  cpu.scounteren = 0;
  cpu.mcountinhibit = 0;
  cpu.satp = 0; /* 分页关闭（MODE=0）：启动阶段直接使用物理地址 */
  cpu.privilege = RISCV_PRIV_M;
  cpu.INTR = false;
}

void init_isa()
{
  /* Load built-in image. */
  memcpy(guest_to_host(CONFIG_MBASE), img, sizeof(img));

  /* Initialize this virtual computer system. */
  restart();
}
