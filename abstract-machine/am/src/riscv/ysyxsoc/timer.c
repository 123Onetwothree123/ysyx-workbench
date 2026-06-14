#include <am.h>
#include <stdint.h>

void __am_timer_init() {
}

void __am_timer_uptime(AM_TIMER_UPTIME_T *uptime) {
  uptime->us = 0;
}

void __am_timer_rtc(AM_TIMER_RTC_T *rtc) {
  rtc->year = 1900;
  rtc->month = 1;
  rtc->day = 1;
  rtc->hour = 0;
  rtc->minute = 0;
  rtc->second = 0;
}