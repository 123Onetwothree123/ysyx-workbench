#include <am.h>
#include <klib.h>
#include <klib-macros.h>
#include <stdint.h>

int main()
{
  ioe_init();

  const auto rtc = io_read(AM_TIMER_RTC);
  if (rtc.year < 1900 || rtc.month < 1 || rtc.month > 12 ||
      rtc.day < 1 || rtc.day > 31 || rtc.hour < 0 || rtc.hour > 23 ||
      rtc.minute < 0 || rtc.minute > 59 || rtc.second < 0 || rtc.second > 59)
  {
    printf("timer RTC fallback FAIL: %d-%d-%d %d:%d:%d\n",
           rtc.year, rtc.month, rtc.day,
           rtc.hour, rtc.minute, rtc.second);
    return 1;
  }

  const uint64_t begin = io_read(AM_TIMER_UPTIME).us;
  uint32_t value = 0x12345678u;
  for (uint32_t i = 0; i < 20000u; ++i)
  {
    value = value * 1664525u + 1013904223u;
    asm volatile("" : "+r"(value));
  }
  const uint64_t end = io_read(AM_TIMER_UPTIME).us;

  if (end <= begin)
  {
    printf("timer uptime FAIL: begin=%llu end=%llu\n",
           (unsigned long long)begin,
           (unsigned long long)end);
    return 1;
  }

  printf("timer uptime PASS: begin=%llu end=%llu checksum=%08x\n",
         (unsigned long long)begin,
         (unsigned long long)end,
         value);
  return 0;
}
