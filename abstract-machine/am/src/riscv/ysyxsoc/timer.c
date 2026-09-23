#include <am.h>
#include <stdint.h>

#define CLINT_MTIME_LO 0x0200bff8
#define CLINT_MTIME_HI 0x0200bffc

#ifndef AM_TIMER_FREQ_MHZ
#define AM_TIMER_FREQ_MHZ 450
#endif
#if AM_TIMER_FREQ_MHZ <= 0
#error "AM_TIMER_FREQ_MHZ must be positive"
#endif

static uint64_t boot_time;

static uint64_t read_time(void) {
  volatile uint32_t *mtime_lo = (volatile uint32_t *)CLINT_MTIME_LO;
  volatile uint32_t *mtime_hi = (volatile uint32_t *)CLINT_MTIME_HI;
  uint32_t hi, lo;
  do {
    hi = *mtime_hi;
    lo = *mtime_lo;
  } while (hi != *mtime_hi);
  return ((uint64_t)hi << 32) | lo;
}

void __am_timer_init() {
  boot_time = read_time();
}

void __am_timer_uptime(AM_TIMER_UPTIME_T *uptime) {
  const uint64_t ticks = read_time() - boot_time;
  uptime->us = ticks / (uint64_t)AM_TIMER_FREQ_MHZ;
}

void __am_timer_rtc(AM_TIMER_RTC_T *rtc) {
  rtc->year = 1900;
  rtc->month = 1;
  rtc->day = 1;
  rtc->hour = 0;
  rtc->minute = 0;
  rtc->second = 0;
}
