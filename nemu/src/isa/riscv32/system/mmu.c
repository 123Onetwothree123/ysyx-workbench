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
#include <memory/vaddr.h>
#include <memory/paddr.h>

#define PTE_V 0x001u
#define PTE_R 0x002u
#define PTE_W 0x004u
#define PTE_X 0x008u
#define PTE_U 0x010u
#define PTE_A 0x040u
#define PTE_D 0x080u

paddr_t isa_mmu_translate(vaddr_t vaddr, int len, int type) {
  // return MEM_RET_FAIL;
  // Sv32：虚拟地址划分为 vpn[1] | vpn[0] | offset，satp.PPN 指向第一级页表
  uint32_t vpn1 = BITS(vaddr, 31, 22);
  uint32_t vpn0 = BITS(vaddr, 21, 12);
  uint32_t offset = BITS(vaddr, 11, 0);

  paddr_t pgdir = (paddr_t)BITS(cpu.satp, 21, 0) << 12;
  word_t pte1 = paddr_read(pgdir + vpn1 * 4, 4);
  assert(pte1 & PTE_V); // 页目录项必须有效
  // PA 只使用 4KB 页面，不接受二级页表项直接作为叶子（超级页）。
  assert((pte1 & (PTE_R | PTE_W | PTE_X)) == 0);
  paddr_t pgtbl = (paddr_t)BITS(pte1, 31, 10) << 12;
  word_t pte0 = paddr_read(pgtbl + vpn0 * 4, 4);
  assert(pte0 & PTE_V); // 页表项必须有效
  assert(pte0 & (PTE_R | PTE_X)); // 叶子页必须至少有 R 或 X

  // MPRV 只影响 M 模式下的 load/store，不影响取指。
  word_t priv = cpu.privilege;
  if (priv == RISCV_PRIV_M && (cpu.mstatus & MSTATUS_MPRV) &&
      type != MEM_TYPE_IFETCH) {
    priv = (cpu.mstatus & MSTATUS_MPP_MASK) >> MSTATUS_MPP_SHIFT;
  }

  if (priv == RISCV_PRIV_U) {
    assert(pte0 & PTE_U); // U 模式只能访问 U 页
  }
  if (type == MEM_TYPE_IFETCH) {
    assert(pte0 & PTE_X);
  } else if (type == MEM_TYPE_READ) {
    assert((pte0 & PTE_R) ||
           ((cpu.mstatus & MSTATUS_MXR) && (pte0 & PTE_X)));
  } else if (type == MEM_TYPE_WRITE) {
    assert(pte0 & PTE_W);
  }

  // A/D 位：AM 填表时可以直接置 1；这里再兜底更新一次。
  word_t new_pte = pte0 | PTE_A;
  if (type == MEM_TYPE_WRITE) {
    new_pte |= PTE_D;
  }
  if (new_pte != pte0) {
    paddr_write(pgtbl + vpn0 * 4, 4, new_pte);
  }

  paddr_t pa = (paddr_t)(BITS(pte0, 31, 10) << 12) | offset;
  // 内核地址是恒等映射；用户虚拟地址（如 0x40000000）映射到任意物理页，
  // 不能再断言 pa == vaddr。
  if (vaddr >= 0x80000000u) {
    assert(pa == vaddr);
  }
  return pa;
}
