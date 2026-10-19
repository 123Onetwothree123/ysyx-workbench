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
