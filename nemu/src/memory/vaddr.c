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

static word_t access_fault_cause(int type) {
  if (type == MEM_TYPE_IFETCH) return 1;
  if (type == MEM_TYPE_READ) return 5;
  return 7;
}

word_t vaddr_ifetch(vaddr_t addr, int len) {
  const vaddr_t va = addr;
  if (isa_mmu_check(addr, len, MEM_TYPE_IFETCH) == MMU_TRANSLATE) {
    addr = isa_mmu_translate(addr, len, MEM_TYPE_IFETCH);
  }
  /*
   * 取指只能来自真实的后备存储器。可变长度取指会把一条 32 位指令拆成
   * 两次 16 位访问，如果高半字已经越过内存末尾，这里必须按指令访问
   * 异常（cause 1）报告，而不是落进 paddr_read 的越界 panic。
   * 对合法的 16 位指令位于内存最后两个字节的情况，单次 2 字节访问仍
   * 在范围内，因此可以正常执行。
   */
  if (!in_backed_memory_range(addr, len)) {
    isa_raise_mem_exception(1, va);
  }
  if (!isa_pmp_check(addr, len, MEM_TYPE_IFETCH)) {
    isa_raise_mem_exception(access_fault_cause(MEM_TYPE_IFETCH), va);
  }
  return paddr_read(addr, len);
}

word_t vaddr_read(vaddr_t addr, int len) {
  const vaddr_t va = addr;
  if (isa_mmu_check(addr, len, MEM_TYPE_READ) == MMU_TRANSLATE) {
    addr = isa_mmu_translate(addr, len, MEM_TYPE_READ);
  }
  if (!isa_pmp_check(addr, len, MEM_TYPE_READ)) {
    isa_raise_mem_exception(access_fault_cause(MEM_TYPE_READ), va);
  }
  return paddr_read(addr, len);
}

void vaddr_write(vaddr_t addr, int len, word_t data) {
  const vaddr_t va = addr;
  if (isa_mmu_check(addr, len, MEM_TYPE_WRITE) == MMU_TRANSLATE) {
    addr = isa_mmu_translate(addr, len, MEM_TYPE_WRITE);
  }
  if (!isa_pmp_check(addr, len, MEM_TYPE_WRITE)) {
    isa_raise_mem_exception(access_fault_cause(MEM_TYPE_WRITE), va);
  }
  paddr_write(addr, len, data);
}
