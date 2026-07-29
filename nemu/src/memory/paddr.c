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
#include <time.h>
#include <stdio.h>

#ifndef CONFIG_DEVICE
// 简易设备stub: 仅为在不开CONFIG_DEVICE时也能跑通AM程序(收集trace用)
// nemu平台: 串口 0xa00003f8(写字节), RTC 0xa0000048(读us时间戳, 低32位/高32位)
// ysyxsoc平台: UART16550 0x10000000(THR写/LSR读), CLINT mtime 0x0200bff8(低)/0x0200bffc(高)
#define STUB_MMIO_BASE  0xa0000000u
#define STUB_MMIO_SIZE  0x00001000u
#define STUB_SERIAL     (STUB_MMIO_BASE + 0x00003f8u)
#define STUB_RTC        (STUB_MMIO_BASE + 0x0000048u)
#define STUB_UART_BASE  0x10000000u
#define STUB_UART_SIZE  0x1000u
#define STUB_CLINT_LO   0x0200bff8u
#define STUB_CLINT_HI   0x0200bffcu
static bool in_stub_mmio(paddr_t addr) { return addr - STUB_MMIO_BASE < STUB_MMIO_SIZE; }
static bool in_stub_uart(paddr_t addr) { return addr - STUB_UART_BASE < STUB_UART_SIZE; }
static uint64_t stub_get_us() {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (uint64_t)ts.tv_sec * 1000000ull + (uint64_t)ts.tv_nsec / 1000ull;
}
static word_t stub_mmio_read(paddr_t addr, int len) {
  if (addr == STUB_RTC)     return (word_t)(stub_get_us() & 0xffffffffull);
  if (addr == STUB_RTC + 4) return (word_t)(stub_get_us() >> 32);
  if (addr == STUB_UART_BASE + 5) return 0x60; // LSR: THR空+发送完成
  if (addr == STUB_CLINT_LO) return (word_t)(stub_get_us() & 0xffffffffull);
  if (addr == STUB_CLINT_HI) return (word_t)(stub_get_us() >> 32);
  return 0;
}
static void stub_mmio_write(paddr_t addr, word_t data) {
  if (addr == STUB_SERIAL || addr == STUB_UART_BASE) { fputc((int)(data & 0xff), stdout); fflush(stdout); }
}
#endif

// 适配新版本difftest
static uint8_t mrom[MROM_SIZE] = {};
static uint8_t sram[SRAM_SIZE] = {};
// flash
static uint8_t flash[FLASH_SIZE] = {};
// sdram(适配ysyxsoc镜像)
static uint8_t sdram[SDRAM_SIZE] = {};

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
  if (in_flash(paddr))
  {
    return flash + paddr - FLASH_BASE;
  }
  if (in_sdram(paddr))
  {
    return sdram + paddr - SDRAM_BASE;
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
    return host_read(flash + addr - FLASH_BASE, len);
  }
  if (in_sdram(addr))
  {
    return host_read(sdram + addr - SDRAM_BASE, len);
  }
#ifndef CONFIG_DEVICE
  if (in_stub_mmio(addr) || in_stub_uart(addr) || addr == STUB_CLINT_LO || addr == STUB_CLINT_HI)
  {
    return stub_mmio_read(addr, len);
  }
#endif
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
  if (in_sdram(addr))
  {
    host_write(sdram + addr - SDRAM_BASE, len, data);
    return;
  }
#ifndef CONFIG_DEVICE
  if (in_stub_mmio(addr) || in_stub_uart(addr) || addr == STUB_CLINT_LO || addr == STUB_CLINT_HI)
  {
    stub_mmio_write(addr, data);
    return;
  }
#endif
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
