#ifndef KLIB_TIME_H__
#define KLIB_TIME_H__
#ifdef __cplusplus
extern "C"
{
#endif
#if defined(__ISA_NATIVE__) && !defined(__NATIVE_USE_KLIB__)
#include_next <time.h>
#else
// 0 UTC 起算的秒数，裸机这里用 AM 的计时器（uptime ms / 1000）
typedef long time_t;
typedef long clock_t;
#define CLOCKS_PER_SEC 1000
struct tm {
  int tm_sec;   // 0-59
  int tm_min;   // 0-59
  int tm_hour;  // 0-23
  int tm_mday;  // 1-31
  int tm_mon;   // 0-11
  int tm_year;  // 1900起算的年减1900
  int tm_wday;  // 0=周日
  int tm_yday;  // 0-365
  int tm_isdst; // 夏令时标志，裸机无意义的用0
};
clock_t clock(void);
time_t time(time_t *t);
struct tm *gmtime(const time_t *t);
struct tm *localtime(const time_t *t);
time_t mktime(struct tm *tm);
char *asctime(const struct tm *tm);
char *ctime(const time_t *t);
double difftime(time_t end, time_t begin);
#endif
#ifdef __cplusplus
}
#endif
#endif
