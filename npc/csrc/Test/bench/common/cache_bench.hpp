#ifndef CACHE_BENCH_HPP
#define CACHE_BENCH_HPP

// bench/cache-*-test 共用的计时与写出口。
#include <stdint.h>

// 写出口：保证被测循环的结果不会被优化掉。
static volatile uint32_t sink;

// 读取 mcycle；各测试只比较相对差值，因此无需处理溢出。
static inline uint32_t rdcycle(void)
{
  uint32_t value;
  asm volatile("csrr %0, mcycle" : "=r"(value));
  return value;
}

#endif
