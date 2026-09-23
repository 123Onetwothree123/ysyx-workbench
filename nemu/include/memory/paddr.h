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

/* Select the ysyxSoC-only physical map.  Ordinary NEMU leaves this disabled,
 * so its 0xa0000000 serial/RTC window is not shadowed by the SoC SDRAM. */
void set_ysyxsoc_memory_mode(bool enable);
bool is_ysyxsoc_memory_mode(void);
bool in_backed_memory_range(paddr_t addr, size_t len);

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
// SRAM的地址0x0f000000，32KB（与 ysyxSoC AXI4RAM 一致）
#define SRAM_BASE 0x0f000000u
#define SRAM_SIZE 0x8000u
// flash
#define FLASH_BASE 0x30000000u
#define FLASH_SIZE 0x10000000u
// PSRAM
#define PSRAM_BASE 0x80000000u
#define PSRAM_SIZE 0x00400000u
// SDRAM(适配ysyxsoc镜像)
#define SDRAM_BASE 0xa0000000u
#define SDRAM_SIZE 0x2000000u
static inline bool in_sdram(paddr_t addr)
{
  return addr - SDRAM_BASE < SDRAM_SIZE;
}
static inline bool in_sdram_range(paddr_t addr, size_t len)
{
  paddr_t offset = addr - SDRAM_BASE;
  return len > 0 && offset < SDRAM_SIZE && len <= SDRAM_SIZE - offset;
}
static inline bool in_flash(paddr_t addr)
{
  return addr - FLASH_BASE < FLASH_SIZE;
}
static inline bool in_flash_range(paddr_t addr, size_t len)
{
  paddr_t offset = addr - FLASH_BASE;
  return len > 0 && offset < FLASH_SIZE && len <= FLASH_SIZE - offset;
}
static inline bool in_psram(paddr_t addr)
{
  return addr - PSRAM_BASE < PSRAM_SIZE;
}
static inline bool in_psram_range(paddr_t addr, size_t len)
{
  paddr_t offset = addr - PSRAM_BASE;
  return len > 0 && offset < PSRAM_SIZE && len <= PSRAM_SIZE - offset;
}
static inline bool in_sram(paddr_t addr)
{
  return addr - SRAM_BASE < SRAM_SIZE;
}
static inline bool in_sram_range(paddr_t addr, size_t len)
{
  paddr_t offset = addr - SRAM_BASE;
  return len > 0 && offset < SRAM_SIZE && len <= SRAM_SIZE - offset;
}

word_t paddr_read(paddr_t addr, int len);
void paddr_write(paddr_t addr, int len, word_t data);

#endif
