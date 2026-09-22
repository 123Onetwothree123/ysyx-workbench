#ifndef KLIB_TIME_H__
#define KLIB_TIME_H__
#ifdef __cplusplus
extern "C"
{
#endif
#if defined(__ISA_NATIVE__)
/* Native AM platform sources include host sys/time.h before this wrapper.
 * Reuse the host time ABI even when differential-testing KLIB functions. */
#include_next <time.h>
#else
#include <stddef.h>
#include <stdint.h>
/* Match the target newlib ABI: RV32 uses a 64-bit time_t, while RV64 uses
 * long.  clock_t is unsigned long on both targets. */
#if __LONG_MAX__ > 0x7fffffffL
typedef long time_t;
#else
typedef int64_t time_t;
#endif
typedef unsigned long clock_t;
#define CLOCKS_PER_SEC 1000
#define TIME_UTC 1
struct timespec {
  time_t tv_sec;
  long tv_nsec;
};
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
struct tm *gmtime_r(const time_t *t, struct tm *result);
struct tm *localtime(const time_t *t);
struct tm *localtime_r(const time_t *t, struct tm *result);
time_t mktime(struct tm *tm);
char *asctime(const struct tm *tm);
char *asctime_r(const struct tm *tm, char *buffer);
char *ctime(const time_t *t);
char *ctime_r(const time_t *t, char *buffer);
double difftime(time_t end, time_t begin);
size_t strftime(char *s, size_t maxsize, const char *format,
                const struct tm *timeptr);
int timespec_get(struct timespec *ts, int base);
#endif
#ifdef __cplusplus
}
#endif
#endif
