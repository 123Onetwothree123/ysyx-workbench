// mdu 性能测试共用的计时 / 统计 / 报告脚手架。
//
// mul-test 与 muldiv-test 只需要各自的负载函数，计时窗口（mcycle + AM
// uptime）、逐阶段累加和末尾的 Scored/Total 输出格式完全一致，抽到这里，
// 保证两个测试的输出口径不会漂移。
#ifndef MDU_COMMON_BENCH_HPP
#define MDU_COMMON_BENCH_HPP

#include <am.h>
#include <cstdint>
#include <klib.h>
#include <klib-macros.h>

struct WorkResult
{
    uint32_t checksum;
    int failures;
};

inline unsigned int total_cycles = 0;
inline uint64_t scored_us = 0;

// 读64位mcycle(高/低字double-read防撕裂)
inline unsigned long long read_mcycle()
{
    uint32_t low, high, high_again;
    do
    {
        asm volatile("csrr %0, mcycleh" : "=r"(high));
        asm volatile("csrr %0, mcycle" : "=r"(low));
        asm volatile("csrr %0, mcycleh" : "=r"(high_again));
    } while (high != high_again);
    return ((unsigned long long)high << 32) | low;
}

// 以下两个函数从 microbench 的 src/bench.c 移植
inline uint64_t uptime()
{
    return io_read(AM_TIMER_UPTIME).us;
}

inline char *format_time(uint64_t us)
{
    static char buf[32];
    uint64_t ms = us / 1000;
    us -= ms * 1000;
    int len = sprintf(buf, "%d.000", (int)ms);
    char *p = &buf[len - 1];
    while (us > 0)
    {
        *(p--) = '0' + us % 10;
        us /= 10;
    }
    return buf;
}

template <class Fn>
inline void measure(const char *name, Fn work, uint32_t &checksum, int &failures)
{
    // 先取uptime再开mcycle窗口, 让MMIO读延迟落在窗口外
    const uint64_t begin_us = uptime();
    const unsigned long long begin = read_mcycle();
    const WorkResult result = work();
    const unsigned long long end = read_mcycle();
    const uint64_t elapsed_us = uptime() - begin_us;
    const unsigned int cycles = (unsigned int)(end - begin);
    total_cycles += cycles;
    scored_us += elapsed_us;
    checksum ^= result.checksum;
    failures += result.failures;
    printf("  %s: %u cycles, %s ms, cs=0x%08x\n", name, cycles, format_time(elapsed_us),
           result.checksum);
    if (result.failures != 0)
    {
        printf("    %s: %d check failures\n", name, result.failures);
    }
}

// 统一的收尾输出；done/done_with_failure 保留各测试自己的 DONE 文案。
inline int report_total(uint32_t checksum, int failures, uint64_t total_us,
                        const char *done, const char *done_with_failure)
{
    printf("total: %u cycles\n", total_cycles);
    printf("checksum=0x%08x failures=%d\n", checksum, failures);
    printf("%s\n", failures == 0 ? done : done_with_failure);
    printf("Scored time: %s ms\n", format_time(scored_us));
    printf("Total  time: %s ms\n", format_time(total_us));
    return failures == 0 ? 0 : 1;
}

#endif
