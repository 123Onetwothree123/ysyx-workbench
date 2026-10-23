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
#include <cpu/difftest.h>
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
#define STUB_CLINT_BASE 0x02000000u
#define STUB_CLINT_SIZE 0x00010000u
#define STUB_CLINT_LO   0x0200bff8u
#define STUB_CLINT_HI   0x0200bffcu
#define STUB_CLINT_CMP_LO 0x02004000u
#define STUB_CLINT_CMP_HI 0x02004004u
#define STUB_PLIC_BASE  0x0c000000u
#define STUB_PLIC_SIZE  0x00400000u
static bool in_stub_mmio(paddr_t addr) { return addr - STUB_MMIO_BASE < STUB_MMIO_SIZE; }
static bool in_stub_uart(paddr_t addr) { return addr - STUB_UART_BASE < STUB_UART_SIZE; }
static bool in_stub_clint(paddr_t addr) { return addr - STUB_CLINT_BASE < STUB_CLINT_SIZE; }
static bool in_stub_plic(paddr_t addr) { return addr - STUB_PLIC_BASE < STUB_PLIC_SIZE; }
static uint64_t stub_mtimecmp = UINT64_MAX;
static uint64_t stub_get_us() {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (uint64_t)ts.tv_sec * 1000000ull + (uint64_t)ts.tv_nsec / 1000ull;
}
static word_t stub_mmio_read(paddr_t addr, int len) {
  if (addr == STUB_RTC)     return (word_t)(stub_get_us() & 0xffffffffull);
  if (addr == STUB_RTC + 4) return (word_t)(stub_get_us() >> 32);
  if (in_stub_uart(addr)) {
    // REF 构建没有 CONFIG_DEVICE：给 xv6 一个最小 16550 视图。
    // 真正的 UART/PLIC 访问在 DiffTest 中由 DUT 注入结果。
    switch (addr - STUB_UART_BASE) {
      case 2: return 0x01; // IIR: no interrupt pending
      case 5: return 0x60; // LSR: THRE | TEMT
      default: return 0;
    }
  }
  if (addr == STUB_CLINT_LO) return (word_t)(stub_get_us() & 0xffffffffull);
  if (addr == STUB_CLINT_HI) return (word_t)(stub_get_us() >> 32);
  if (addr == STUB_CLINT_CMP_LO) return (word_t)(stub_mtimecmp & 0xffffffffull);
  if (addr == STUB_CLINT_CMP_HI) return (word_t)(stub_mtimecmp >> 32);
  if (in_stub_plic(addr)) return 0;
  return 0;
}
static void stub_mmio_write(paddr_t addr, word_t data) {
  if (addr == STUB_SERIAL || addr == STUB_UART_BASE) {
    // DiffTest REF 不应重复打印 DUT 的 UART 输出。
#ifndef CONFIG_TARGET_SHARE
    fputc((int)(data & 0xff), stdout);
    fflush(stdout);
#endif
  } else if (addr == STUB_CLINT_CMP_LO || addr == STUB_CLINT_CMP_HI) {
    const unsigned shift = (addr == STUB_CLINT_CMP_LO) ? 0 : 32;
    stub_mtimecmp = (stub_mtimecmp & ~(0xffffffffull << shift)) |
                    ((uint64_t)(uint32_t)data << shift);
  }
  // PLIC 写（priority/enable/threshold/complete）在 REF 中无副作用；
  // 中断由 DUT 通过 difftest_trap 注入。
}
#endif

// 适配新版本difftest
static uint8_t sram[SRAM_SIZE] = {};
// flash
static uint8_t flash[FLASH_SIZE] = {};
static uint8_t psram[PSRAM_SIZE] = {};
// sdram(适配ysyxsoc镜像)
static uint8_t sdram[SDRAM_SIZE] = {};
static bool ysyxsoc_memory_mode = false;

#if defined(CONFIG_PMEM_MALLOC)
static uint8_t *pmem = NULL;
#else // CONFIG_PMEM_GARRAY
static uint8_t pmem[CONFIG_MSIZE] PG_ALIGN = {};
#endif

void set_ysyxsoc_memory_mode(bool enable)
{
  ysyxsoc_memory_mode = enable;
}

bool is_ysyxsoc_memory_mode(void)
{
  return ysyxsoc_memory_mode;
}

bool in_backed_memory_range(paddr_t addr, size_t len)
{
  if (!ysyxsoc_memory_mode && in_pmem_range(addr, len))
  {
    return true;
  }
  return ysyxsoc_memory_mode &&
         (in_sram_range(addr, len) || in_flash_range(addr, len) ||
          in_psram_range(addr, len) || in_sdram_range(addr, len));
}

uint8_t *guest_to_host(paddr_t paddr)
{
  if (ysyxsoc_memory_mode && in_sram(paddr))
  {
    return sram + paddr - SRAM_BASE;
  }
  if (ysyxsoc_memory_mode && in_flash(paddr))
  {
    return flash + paddr - FLASH_BASE;
  }
  if (ysyxsoc_memory_mode && in_psram(paddr))
  {
    return psram + paddr - PSRAM_BASE;
  }
  if (ysyxsoc_memory_mode && in_sdram(paddr))
  {
    return sdram + paddr - SDRAM_BASE;
  }
  return pmem + paddr - CONFIG_MBASE;
}

static bool host_address_in(const uint8_t *address,
                            const uint8_t *base, size_t size)
{
  const uintptr_t value = (uintptr_t)address;
  const uintptr_t begin = (uintptr_t)base;
  return value >= begin && value - begin < size;
}

paddr_t host_to_guest(uint8_t *haddr)
{
  if (ysyxsoc_memory_mode)
  {
    if (host_address_in(haddr, sram, SRAM_SIZE))
      return SRAM_BASE + (paddr_t)(haddr - sram);
    if (host_address_in(haddr, flash, FLASH_SIZE))
      return FLASH_BASE + (paddr_t)(haddr - flash);
    if (host_address_in(haddr, psram, PSRAM_SIZE))
      return PSRAM_BASE + (paddr_t)(haddr - psram);
    if (host_address_in(haddr, sdram, SDRAM_SIZE))
      return SDRAM_BASE + (paddr_t)(haddr - sdram);
  }
  return (paddr_t)((uintptr_t)haddr - (uintptr_t)pmem) + CONFIG_MBASE;
}

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

  if (likely(!ysyxsoc_memory_mode && in_pmem_range(addr, len)))
  {
    word_t ret = pmem_read(addr, len);
#ifdef CONFIG_MTRACE
    Log("mtrace 读内存追踪：pc = " FMT_WORD ", addr = " FMT_PADDR ", len = %d, data = " FMT_WORD,
        cpu.pc, addr, len, ret);
#endif
    return ret;
  }
  if (ysyxsoc_memory_mode && in_sram_range(addr, len))
  {
    return host_read(sram + addr - SRAM_BASE, len);
  }
  if (ysyxsoc_memory_mode && in_flash_range(addr, len))
  {
    return host_read(flash + addr - FLASH_BASE, len);
  }
  if (ysyxsoc_memory_mode && in_psram_range(addr, len))
  {
    return host_read(psram + addr - PSRAM_BASE, len);
  }
  if (ysyxsoc_memory_mode && in_sdram_range(addr, len))
  {
    return host_read(sdram + addr - SDRAM_BASE, len);
  }
#ifndef CONFIG_DEVICE
  if (in_stub_mmio(addr) || in_stub_uart(addr) || in_stub_clint(addr) ||
      in_stub_plic(addr))
  {
    difftest_skip_ref();
    return stub_mmio_read(addr, len);
  }
#endif
#ifdef CONFIG_DEVICE
  if (!in_pmem(addr))
  {
    difftest_skip_ref();
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
  if (likely(!ysyxsoc_memory_mode && in_pmem_range(addr, len)))
  {
#ifdef CONFIG_MTRACE
    Log("mtrace写内存追踪：pc = " FMT_WORD ", addr = " FMT_PADDR ", len = %d, data = " FMT_WORD,
        cpu.pc, addr, len, data);
#endif
    pmem_write(addr, len, data);
    return;
  }
  if (ysyxsoc_memory_mode && in_sram_range(addr, len))
  {
    host_write(sram + addr - SRAM_BASE, len, data);
    return;
  }
  if (ysyxsoc_memory_mode && in_flash_range(addr, len))
  {
    return;
  }
  if (ysyxsoc_memory_mode && in_psram_range(addr, len))
  {
    host_write(psram + addr - PSRAM_BASE, len, data);
    return;
  }
  if (ysyxsoc_memory_mode && in_sdram_range(addr, len))
  {
    host_write(sdram + addr - SDRAM_BASE, len, data);
    return;
  }
#ifndef CONFIG_DEVICE
  if (in_stub_mmio(addr) || in_stub_uart(addr) || in_stub_clint(addr) ||
      in_stub_plic(addr))
  {
    difftest_skip_ref();
    stub_mmio_write(addr, data);
    return;
  }
#endif
#ifdef CONFIG_DEVICE
  if (!in_pmem(addr))
  {
    difftest_skip_ref();
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
