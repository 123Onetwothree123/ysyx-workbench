// muldiv-perf: 复杂算法负载的乘除法性能对比测试(RV32I软件乘除 vs RV32IM硬件乘除)
//
// 负载是一套128位整数密码学工具链(全部用 * / % 和加减/比较/移位实现,
// 不是把简单运算循环堆起来):
//   fib-doubling : Fibonacci快速倍增F(n) mod 2^128, 纯乘法依赖链
//   gcd-euclid   : Euclid辗转相除+整除性自检, 除法密集
//   modexp-fermat: 模幂(费马小定理+指数可加性双重自检)
//   miller-rabin : Miller-Rabin素性测试(含Carmichael数/强伪素数陷阱)
//   rsa-roundtrip: RSA密钥生成(扩展Euclid求逆)+加解密往返
//
// 两个ARCH编译同一份源码:
//   make ARCH=riscv32i-ysyxsoc  run    # * / % 走libgcc软件例程(__mulsi3/__udivsi3/...)
//   make ARCH=riscv32im-ysyxsoc run    # * / % 走硬件M扩展(mul/div/rem/mulhu)
// 想拿NPC侧完整统计(周期/指令/IPC/阻塞)就把 run 换成 perf
//
// 计时显示移植自 am-kernels/benchmarks/microbench/src/bench.c:
//   每组同时统计mcycle(周期)和AM_TIMER_UPTIME(微秒), 末尾输出
//   Scored time(各负载累计)/Total time(整个测试墙钟), 格式与microbench一致
//   注意: ysyxSoC的CLINT mtime每周期+1, 这里的"us/ms"刻度实际是仿真周期
//
// 可调参数: -DMULDIV_SCALE=n 放大各阶段规模(默认2)
#include <am.h>
#include <klib.h>
#include <klib-macros.h>
#include <cstdint>
#include "../common/bench.hpp"
#include "workload.hpp"

#ifndef MULDIV_SCALE
#define MULDIV_SCALE 2
#endif

// 基础运算自检(大整数库的正确性由各算法阶段内置校验负责)
static int sanity_check()
{
    int mask = 0;
    if (12345u * 6789u != 83810205u)
    {
        mask |= 1;
    }
    if (1000000000u / 12345u != 81004u)
    {
        mask |= 2;
    }
    if (1000000000u % 12345u != 5620u)
    {
        mask |= 4;
    }
    if (-1000000 / 7 != -142857)
    {
        mask |= 8;
    }
    if (-1000000 % 7 != -1)
    {
        mask |= 16;
    }
    if (((unsigned long long)0xFFFFFFFFu * 0xFFFFFFFFu) != 0xFFFFFFFE00000001ull)
    {
        mask |= 32;
    }
    return mask;
}

int main()
{
    ioe_init();
    printf("muldiv-perf: scale=%d\n", MULDIV_SCALE);
    const int sanity = sanity_check();
    if (sanity == 0)
    {
        printf("sanity: PASS\n");
    }
    else
    {
        printf("sanity: FAIL mask=0x%x\n", sanity);
    }

    uint32_t checksum = 0;
    int failures = sanity == 0 ? 0 : 1;
    const uint64_t total_begin = uptime();

    measure(
        "fib-doubling", [] { return run_phase_fib(MULDIV_SCALE); }, checksum, failures);
    measure(
        "gcd-euclid", [] { return run_phase_gcd(MULDIV_SCALE); }, checksum, failures);
    measure(
        "modexp-fermat", [] { return run_phase_modexp(MULDIV_SCALE); }, checksum, failures);
    measure(
        "miller-rabin", [] { return run_phase_miller(MULDIV_SCALE); }, checksum, failures);
    measure(
        "rsa-roundtrip", [] { return run_phase_rsa(MULDIV_SCALE); }, checksum, failures);

    const uint64_t total_us = uptime() - total_begin;

    return report_total(checksum, failures, total_us, "MULDIV PERF TEST DONE",
                        "MULDIV PERF TEST DONE (CHECK FAIL)");
}
