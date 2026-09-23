#include <am.h>
#include <stdint.h>

// RISC-V标准CLINT的mtime基址（0x02000000 + 0xBFF8），从SDRAM窗口(0xa0000000)移出来避免地址重叠
#define RTC_ADDR 0x0200bff8
#define inl(addr) (*(volatile uint32_t *)(addr))

#ifndef AM_TIMER_FREQ_MHZ
#define AM_TIMER_FREQ_MHZ 450
#endif
#if AM_TIMER_FREQ_MHZ <= 0
#error "AM_TIMER_FREQ_MHZ must be positive"
#endif

static uint64_t boot_time = 0;
static uint64_t read_time();
static uint64_t ticks_to_us(uint64_t ticks);

void __am_timer_init()
{
  // 记录启动时间
  boot_time = read_time();
}

void __am_timer_uptime(AM_TIMER_UPTIME_T *uptime)
{
  // uptime->us = 0;
  uptime->us = ticks_to_us(read_time() - boot_time);
}

void __am_timer_rtc(AM_TIMER_RTC_T *rtc)
{
  // The direct NPC only implements CLINT mtime, not calendar registers.
  // Return the conventional AM fallback epoch instead of issuing loads to
  // unmapped addresses next to mtime.
  rtc->second = 0;
  rtc->minute = 0;
  rtc->hour = 0;
  rtc->day = 1;
  rtc->month = 1;
  rtc->year = 1900;
}

static uint64_t read_time()
{
  while (1)
  {
    uint32_t HighBefore = inl(RTC_ADDR + 4);
    uint32_t low = inl(RTC_ADDR);
    uint32_t HighAfter = inl(RTC_ADDR + 4);
    // 如果两次high一样，说明读low的过程中没有发生32位进位
    if (HighBefore == HighAfter)
    {
      return ((uint64_t)HighBefore << 32) | low;
    }
  }
}

static uint64_t ticks_to_us(uint64_t ticks)
{
  // mtime advances once per CPU clock, so an integer-MHz clock contributes
  // exactly AM_TIMER_FREQ_MHZ ticks per microsecond.
  return ticks / (uint64_t)AM_TIMER_FREQ_MHZ;
}
