#include <am.h>
#include <stdint.h>

// 他妈的我也不知道为什么会这样，没办法导入nemu.h，我估计是npc的makefile没有包含nemu的头文件路径，只能手动设定了
// RISC-V标准CLINT的mtime基址（0x02000000 + 0xBFF8），从SDRAM窗口(0xa0000000)移出来避免地址重叠
#define RTC_ADDR 0x0200bff8
#define RTC_YEAR_ADDR (RTC_ADDR + 0x10)
#define RTC_MONTH_ADDR (RTC_ADDR + 0x14)
#define RTC_DAY_ADDR (RTC_ADDR + 0x18)
#define RTC_HOUR_ADDR (RTC_ADDR + 0x1C)
#define RTC_MINUTE_ADDR (RTC_ADDR + 0x20)
#define RTC_SECOND_ADDR (RTC_ADDR + 0x24)
#define SIM_FREQ_ADDR 0xa0000070
//新加的CLINT的
#define inl(addr) (*(volatile uint32_t *)(addr))

// 自己写的
static uint64_t boot_time = 0;
static uint64_t read_time();
static uint64_t read_sim_freq();
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
  /*
  rtc->second = 0;
  rtc->minute = 0;
  rtc->hour   = 0;
  rtc->day    = 0;
  rtc->month  = 0;
  rtc->year   = 1900;
  */
  /*
   time_t t = time(NULL);      // 查了下time看到这个函数，从1970/1/1 UTC0到西安在的描述
   struct tm *tm = gmtime(&t); // 把时间改成UTC的结构体tm
   rtc->second = tm->tm_sec;
   rtc->minute = tm->tm_min;
   rtc->hour = tm->tm_hour;
   rtc->day = tm->tm_mday;
   rtc->month = tm->tm_mon + 1;    // 因为tm_mon是0-11，加1变成1-12
   rtc->year = tm->tm_year + 1900; // 查了下才知道tm_yea是从1900起的偏移量
   */
  // 从 NPC 的 MMIO 读取 RTC
  rtc->year = inl(RTC_YEAR_ADDR);
  rtc->month = inl(RTC_MONTH_ADDR);
  rtc->day = inl(RTC_DAY_ADDR);
  rtc->hour = inl(RTC_HOUR_ADDR);
  rtc->minute = inl(RTC_MINUTE_ADDR);
  rtc->second = inl(RTC_SECOND_ADDR);
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

static uint64_t read_sim_freq()
{
  uint32_t low = inl(SIM_FREQ_ADDR);
  uint32_t high = inl(SIM_FREQ_ADDR + 4);
  uint64_t freq = ((uint64_t)high << 32) | low;
  return freq == 0 ? 1 : freq;
}

static uint64_t ticks_to_us(uint64_t ticks)
{
  uint64_t freq = read_sim_freq();
  return (ticks / freq) * 1000000ULL + (ticks % freq) * 1000000ULL / freq;
}
