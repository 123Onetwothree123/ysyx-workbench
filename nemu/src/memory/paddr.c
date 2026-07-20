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

#include <memory/host.h>
#include <memory/paddr.h>
#include <device/mmio.h>
#include <isa.h>

// 适配新版本difftest
static uint8_t mrom[MROM_SIZE] = {};
static uint8_t sram[SRAM_SIZE] = {};
// flash
static uint8_t flash[FLASH_SIZE] = {};

#if defined(CONFIG_PMEM_MALLOC)
static uint8_t *pmem = NULL;
#else // CONFIG_PMEM_GARRAY
static uint8_t pmem[CONFIG_MSIZE] PG_ALIGN = {};
#endif

uint8_t *guest_to_host(paddr_t paddr)
{
  if (in_mrom(paddr))
  {
    return mrom + paddr - MROM_BASE;
  }
  if (in_sram(paddr))
  {
    return sram + paddr - SRAM_BASE;
  }
  return pmem + paddr - CONFIG_MBASE;
}
paddr_t host_to_guest(uint8_t *haddr) { return haddr - pmem + CONFIG_MBASE; }

static word_t pmem_read(paddr_t addr, int len)
{
  word_t ret = host_read(guest_to_host(addr), len);
  return ret;
}

static void pmem_write(paddr_t addr, int len, word_t data)
{
  host_write(guest_to_host(addr), len, data);
}

static void out_of_bound(paddr_t addr, int len)
{
  panic("address = " FMT_PADDR ", len = %d is out of bound of pmem [" FMT_PADDR ", " FMT_PADDR "] at pc = " FMT_WORD,
        addr, len, PMEM_LEFT, PMEM_RIGHT, cpu.pc);
}
void init_mem()
{
#if defined(CONFIG_PMEM_MALLOC)
  pmem = malloc(CONFIG_MSIZE);
  assert(pmem);
#endif
  IFDEF(CONFIG_MEM_RANDOM, memset(pmem, rand(), CONFIG_MSIZE));
  Log("physical memory area [" FMT_PADDR ", " FMT_PADDR "]", PMEM_LEFT, PMEM_RIGHT);
}

word_t paddr_read(paddr_t addr, int len)
{

  if (likely(in_pmem_range(addr, len)))
  {
    word_t ret = pmem_read(addr, len);
#ifdef CONFIG_MTRACE
    Log("mtrace 读内存追踪：pc = " FMT_WORD ", addr = " FMT_PADDR ", len = %d, data = " FMT_WORD,
        cpu.pc, addr, len, ret);
#endif
    return ret;
  }
  if (in_mrom(addr))
  {
    return host_read(mrom + addr - MROM_BASE, len);
  }
  if (in_sram(addr))
  {
    return host_read(sram + addr - SRAM_BASE, len);
  }
  if (in_flash(addr))
  {
    return flash[addr - FLASH_BASE];
  }
#ifdef CONFIG_DEVICE
  if (!in_pmem(addr))
  {
    word_t ret = mmio_read(addr, len);
#ifdef CONFIG_MTRACE
    Log("mtrace 读内存追踪(CONFIG_DEVICE)：pc = " FMT_WORD ", addr = " FMT_PADDR ", len = %d, data = " FMT_WORD,
        cpu.pc, addr, len, ret);
#endif
    return ret;
  }
#endif
  out_of_bound(addr, len);
  return 0;
}

void paddr_write(paddr_t addr, int len, word_t data)
{
  if (likely(in_pmem_range(addr, len)))
  {
#ifdef CONFIG_MTRACE
    Log("mtrace写内存追踪：pc = " FMT_WORD ", addr = " FMT_PADDR ", len = %d, data = " FMT_WORD,
        cpu.pc, addr, len, data);
#endif
    pmem_write(addr, len, data);
    return;
  }
  if (in_mrom(addr))
  {
    host_write(mrom + addr - MROM_BASE, len, data);
    return;
  }
  if (in_sram(addr))
  {
    host_write(sram + addr - SRAM_BASE, len, data);
    return;
  }
  if (in_flash(addr))
  {
    return;
  }
#ifdef CONFIG_DEVICE
  if (!in_pmem(addr))
  {
#ifdef CONFIG_MTRACE
    Log("mtrace写内存追踪(CONFIG_DEVICE)：pc = " FMT_WORD ", addr = " FMT_PADDR ", len = %d, data = " FMT_WORD,
        cpu.pc, addr, len, data);
#endif
    mmio_write(addr, len, data);
    return;
  }
#endif
  out_of_bound(addr, len);
}
