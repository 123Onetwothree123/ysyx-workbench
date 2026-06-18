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

#ifndef __MEMORY_PADDR_H__
#define __MEMORY_PADDR_H__

#include <common.h>

#define PMEM_LEFT ((paddr_t)CONFIG_MBASE)
#define PMEM_RIGHT ((paddr_t)CONFIG_MBASE + CONFIG_MSIZE - 1)
#define RESET_VECTOR (PMEM_LEFT + CONFIG_PC_RESET_OFFSET)

/* convert the guest physical address in the guest program to host virtual address in NEMU */
uint8_t *guest_to_host(paddr_t paddr);
/* convert the host virtual address in NEMU to guest physical address in the guest program */
paddr_t host_to_guest(uint8_t *haddr);

static inline bool in_pmem(paddr_t addr)
{
  return addr - CONFIG_MBASE < CONFIG_MSIZE;
}
static inline bool in_pmem_range(paddr_t addr, size_t len)
{
  if (len <= 0)
  {
    return false;
  }
  paddr_t offset = addr - CONFIG_MBASE;
  return offset < CONFIG_MSIZE &&
         (paddr_t)len <= CONFIG_MSIZE - offset;
}

// 哎哟，他妈的上个学期还只会C++，不会C，现在看paddr.h和c，这写的什么代码，我都没不好意思看，现在又不敢乱动，现在还要在这里补充重新适配difftest的代码
// MROM地址0x20000000，4KB
#define MROM_BASE 0x20000000u
#define MROM_SIZE 0x1000u
// SRAM的地址0x0f000000，8KB
#define SRAM_BASE 0x0f000000u
#define SRAM_SIZE 0x2000u
static inline bool in_mrom(paddr_t addr)
{
  return addr - MROM_BASE < MROM_SIZE;
}
static inline bool in_sram(paddr_t addr)
{
  return addr - SRAM_BASE < SRAM_SIZE;
}

word_t paddr_read(paddr_t addr, int len);
void paddr_write(paddr_t addr, int len, word_t data);

#endif
