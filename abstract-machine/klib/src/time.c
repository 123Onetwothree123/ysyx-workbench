#include <am.h>
#include <klib.h>
#include <klib-macros.h>
#include <time.h>
#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
clock_t clock(void)
{
  return (clock_t)(io_read(AM_TIMER_UPTIME).us / 1000);
}
time_t time(time_t *t)
{
  // 接AM的计时器，uptime是自启动起的微秒数，除以1e6得秒
  uint64_t us = io_read(AM_TIMER_UPTIME).us;
  time_t s = (time_t)(us / 1000000);
  if (t)
  {
    *t = s;
  }
  return s;
}
struct tm *gmtime(const time_t *t)
{
  panic("gmtime: Not implemented");
}
struct tm *localtime(const time_t *t)
{
  panic("localtime: Not implemented");
}
time_t mktime(struct tm *tm)
{
  panic("mktime: Not implemented");
}
char *asctime(const struct tm *tm)
{
  panic("asctime: Not implemented");
}
char *ctime(const time_t *t)
{
  panic("ctime: Not implemented");
}
double difftime(time_t end, time_t begin)
{
  return (double)(end - begin);
}
#endif
