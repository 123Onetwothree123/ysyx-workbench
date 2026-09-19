// workload.hpp: 乘除法性能测试的复杂算法负载
// 一个"128位整数密码学工具链": 快速倍增斐波那契 -> Euclid/GCD ->
// 费马小定理模幂 -> Miller-Rabin素性测试 -> RSA往返
// 每个阶段自带正确性校验, 返回校验和与失败数
#ifndef MULDIV_WORKLOAD_HPP
#define MULDIV_WORKLOAD_HPP

#include <cstdint>

struct WorkResult
{
    uint32_t checksum;
    int failures;
};

WorkResult run_phase_fib(int scale);
WorkResult run_phase_gcd(int scale);
WorkResult run_phase_modexp(int scale);
WorkResult run_phase_miller(int scale);
WorkResult run_phase_rsa(int scale);

#endif
