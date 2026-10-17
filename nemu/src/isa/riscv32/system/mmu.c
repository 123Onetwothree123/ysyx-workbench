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

paddr_t isa_mmu_translate(vaddr_t vaddr, int len, int type) {
  // return MEM_RET_FAIL;
  // Sv32：虚拟地址划分为 vpn[1] | vpn[0] | offset，satp.PPN 指向第一级页表
  uint32_t vpn1 = BITS(vaddr, 31, 22);
  uint32_t vpn0 = BITS(vaddr, 21, 12);
  uint32_t offset = BITS(vaddr, 11, 0);

  paddr_t pgdir = (paddr_t)BITS(cpu.satp, 21, 0) << 12;
  word_t pte1 = paddr_read(pgdir + vpn1 * 4, 4);
  assert(BITS(pte1, 0, 0)); // 页目录项必须有效（V 位），否则说明软件填写页表出错
  paddr_t pgtbl = (paddr_t)BITS(pte1, 31, 10) << 12;
  word_t pte0 = paddr_read(pgtbl + vpn0 * 4, 4);
  assert(BITS(pte0, 0, 0)); // 页表项必须有效（V 位）

  paddr_t pa = (paddr_t)(BITS(pte0, 31, 10) << 12) | offset;
  // 此时 Nanos-lite 运行在内核恒等映射上，转换结果 pa 必定与 va 相同
  // （里程碑1的调试断言；做"在分页机制上运行用户进程"时要删掉）
  assert(pa == vaddr);
  return pa;
}
