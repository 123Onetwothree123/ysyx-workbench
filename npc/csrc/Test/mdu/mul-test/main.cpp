// mul-test: 纯乘法大规模压力测试(不含除法), 专门对比M扩展乘法器
//
// 为什么单独做这个测试: muldiv-test是乘除混合的真实负载, 除法器(三套乘法方案完全相同)
// 和访存停顿把乘法器差异稀释了(实测移位乘法器只慢3.2%); 本测试全部是乘法运算,
// 且工作集刻意做小(几KB), 让乘法器的延迟/吞吐差异尽量暴露出来。
//
// 负载(只用 * 和加减/移位, 没有任何除法):
//   serial   依赖乘链 x=x*K+C         —— 测MDU延迟(每轮等上一轮结果)
//   parallel 8路独立乘链              —— 测吞吐(单在途MDU下的延迟重叠)
//   bigmul   256位schoolbook大整数乘  —— 真实算法, 16位limb×16位limb→32位乘法
//   fib      64位斐波那契快速倍增     —— 纯乘法log算法, 带已知值自检
//   matmul   8x8整数矩阵乘            —— 带单位阵回代自检
//
// 计时: mcycle(周期) + AM_TIMER_UPTIME(时间), 末尾输出microbench风格的Scored/Total
// 规模: -DMUL_SCALE=n 整体放大(默认2)
#include <am.h>
#include <klib.h>
#include <klib-macros.h>
#include <cstdint>

#ifndef MUL_SCALE
#define MUL_SCALE 2
#endif

static unsigned int total_cycles = 0;
static uint64_t scored_us = 0;

// 读64位mcycle(高/低字double-read防撕裂)
static unsigned long long read_mcycle()
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
static uint64_t uptime()
{
    return io_read(AM_TIMER_UPTIME).us;
}

static char *format_time(uint64_t us)
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

struct WorkResult
{
    uint32_t checksum;
    int failures;
};

// ---------------- 1) 依赖乘链: 测MDU延迟 ----------------
static WorkResult phase_serial(int scale)
{
    WorkResult res{0u, 0};
    // 乘数/加数从volatile读一次, 防常量折叠和强度削减(rv32i下走__mulsi3)
    volatile uint32_t k_src = 2654435761u;
    volatile uint32_t c_src = 0x9E3779B9u;
    const uint32_t k = k_src;
    const uint32_t c = c_src;
    uint32_t x = 0x12345678u ^ (uint32_t)scale;
    const uint32_t iters = 400000u * (uint32_t)scale;
    for (uint32_t i = 0; i < iters; ++i)
    {
        x = x * k + c; // 每轮都依赖上一轮的乘法结果
    }
    res.checksum = x;
    return res;
}

// ---------------- 2) 8路独立乘链: 测吞吐 ----------------
static WorkResult phase_parallel(int scale)
{
    WorkResult res{0u, 0};
    volatile uint32_t k_src = 0x01000193u;
    const uint32_t k = k_src;
    const uint32_t rounds = 100000u * (uint32_t)scale;
    uint32_t a = 0x11111111u;
    uint32_t b = 0x22222222u;
    uint32_t c = 0x33333333u;
    uint32_t d = 0x44444444u;
    uint32_t e = 0x55555555u;
    uint32_t f = 0x66666666u;
    uint32_t g = 0x77777777u;
    uint32_t h = 0x88888888u;
    for (uint32_t i = 0; i < rounds; ++i)
    {
        a = a * k + 1u;
        b = b * k + 2u;
        c = c * k + 3u;
        d = d * k + 4u;
        e = e * k + 5u;
        f = f * k + 6u;
        g = g * k + 7u;
        h = h * k + 8u;
    }
    res.checksum = a ^ b ^ c ^ d ^ e ^ f ^ g ^ h;
    return res;
}

// ---------------- 3) 256位大整数schoolbook乘法 ----------------
static constexpr int BM_LIMBS = 16; // 16位limb × 16 = 256位

static void bm_mul(uint16_t *r, const uint16_t *x, const uint16_t *y)
{
    // 低256位乘积: 每个limb乘积都是一条32位乘法
    for (int i = 0; i < BM_LIMBS; ++i)
    {
        r[i] = 0;
    }
    for (int i = 0; i < BM_LIMBS; ++i)
    {
        uint32_t carry = 0;
        for (int j = 0; j + i < BM_LIMBS; ++j)
        {
            const uint32_t t = (uint32_t)r[i + j] + (uint32_t)x[i] * (uint32_t)y[j] + carry;
            r[i + j] = (uint16_t)(t & 0xFFFFu);
            carry = t >> 16;
        }
    }
}

static WorkResult phase_bigmul(int scale)
{
    WorkResult res{0u, 0};
    static uint16_t x[BM_LIMBS];
    static uint16_t y[BM_LIMBS];
    static uint16_t t[BM_LIMBS];
    for (int i = 0; i < BM_LIMBS; ++i)
    {
        x[i] = (uint16_t)(0x1000u + 0x0101u * (uint32_t)i);
        y[i] = (uint16_t)(0x2000u + 0x0202u * (uint32_t)i);
    }
    // 已知值自检: x*y 的低256位(期望值用Python算好后写死)
    static const uint16_t expect[BM_LIMBS] = {
        0x0000u, 0x4200u, 0xc842u, 0x96cau, 0xb19du, 0x1cbeu, 0xdc32u, 0xf3fbu,
        0x681fu, 0x3ca2u, 0x7587u, 0x16d2u, 0x2488u, 0xa2acu, 0x9542u, 0x004fu};
    bm_mul(t, x, y);
    for (int i = 0; i < BM_LIMBS; ++i)
    {
        if (t[i] != expect[i])
        {
            ++res.failures;
        }
    }
    // 负载: 大数乘法链(每轮136条16x16→32位乘法), 结果回代保持依赖
    const uint32_t iters = 2000u * (uint32_t)scale;
    uint32_t acc = 0;
    for (uint32_t i = 0; i < iters; ++i)
    {
        bm_mul(t, x, y);
        acc = acc * 31u + ((uint32_t)t[0] << 16) + (uint32_t)t[1];
        for (int k = 0; k < BM_LIMBS; ++k)
        {
            x[k] = y[k];
            y[k] = t[k];
        }
    }
    bm_mul(t, x, y);
    res.checksum = acc ^ ((uint32_t)t[0] << 8) ^ (uint32_t)t[1];
    return res;
}

// ---------------- 4) 64位斐波那契快速倍增(纯乘法) ----------------
static uint64_t fib_pair64(uint64_t n, uint64_t &out_b)
{
    // F(n)与F(n+1), 模2^64自然回绕; 每个bit做3次64位乘法
    uint64_t a = 0;
    uint64_t b = 1;
    for (int i = 63; i >= 0; --i)
    {
        const uint64_t c = a * ((b << 1) - a);
        const uint64_t d = a * a + b * b;
        if (((n >> i) & 1u) != 0u)
        {
            a = d;
            b = c + d;
        }
        else
        {
            a = c;
            b = d;
        }
    }
    out_b = b;
    return a;
}

static WorkResult phase_fib(int scale)
{
    WorkResult res{0u, 0};
    static const uint64_t known[18] = {
        0ull, 1ull, 1ull, 2ull, 3ull, 5ull, 8ull, 13ull, 21ull, 34ull, 55ull, 89ull,
        144ull, 233ull, 377ull, 610ull, 7540113804746346429ull, 12200160415121876738ull};
    uint64_t b = 0;
    for (uint32_t n = 0; n < 16; ++n)
    {
        const uint64_t a = fib_pair64(n, b);
        if (a != known[n])
        {
            ++res.failures;
        }
        res.checksum = res.checksum * 31u + (uint32_t)a;
    }
    uint64_t a = fib_pair64(92, b);
    if (a != known[16])
    {
        ++res.failures;
    }
    a = fib_pair64(93, b);
    if (a != known[17])
    {
        ++res.failures;
    }
    // 负载: 一批大下标的快速倍增
    const uint32_t count = 1024u * (uint32_t)scale;
    for (uint32_t k = 0; k < count; ++k)
    {
        const uint64_t n = 1000000007ull + (uint64_t)k * 2654435761ull;
        a = fib_pair64(n, b);
        res.checksum = res.checksum * 31u + (uint32_t)(a ^ b);
    }
    return res;
}

// ---------------- 5) 8x8整数矩阵乘 ----------------
static constexpr int MM_N = 8;

static void mm_mul(uint32_t c[MM_N][MM_N], const uint32_t a[MM_N][MM_N], const uint32_t b[MM_N][MM_N])
{
    for (int i = 0; i < MM_N; ++i)
    {
        for (int j = 0; j < MM_N; ++j)
        {
            uint32_t s = 0;
            for (int k = 0; k < MM_N; ++k)
            {
                s += a[i][k] * b[k][j];
            }
            c[i][j] = s;
        }
    }
}

static WorkResult phase_matmul(int scale)
{
    WorkResult res{0u, 0};
    static uint32_t a[MM_N][MM_N];
    static uint32_t b[MM_N][MM_N];
    static uint32_t c[MM_N][MM_N];
    for (int i = 0; i < MM_N; ++i)
    {
        for (int j = 0; j < MM_N; ++j)
        {
            a[i][j] = ((uint32_t)i * (uint32_t)MM_N + (uint32_t)j) * 0x01010101u + 1u;
            b[i][j] = (i == j) ? 1u : 0u; // 单位阵
        }
    }
    // 自检: A * I == A
    mm_mul(c, a, b);
    for (int i = 0; i < MM_N; ++i)
    {
        for (int j = 0; j < MM_N; ++j)
        {
            if (c[i][j] != a[i][j])
            {
                ++res.failures;
            }
        }
    }
    // 负载: A×B 反复迭代(模2^32), 每轮512条乘法
    for (int i = 0; i < MM_N; ++i)
    {
        for (int j = 0; j < MM_N; ++j)
        {
            b[i][j] = a[j][i] + 0x9E3779B9u;
        }
    }
    const uint32_t iters = 400u * (uint32_t)scale;
    for (uint32_t t = 0; t < iters; ++t)
    {
        mm_mul(c, a, b);
        for (int i = 0; i < MM_N; ++i)
        {
            for (int j = 0; j < MM_N; ++j)
            {
                a[i][j] = c[i][j];
            }
        }
    }
    uint32_t acc = 0;
    for (int i = 0; i < MM_N; ++i)
    {
        for (int j = 0; j < MM_N; ++j)
        {
            acc = acc * 31u + a[i][j];
        }
    }
    res.checksum = acc;
    return res;
}

template <class Fn>
static void measure(const char *name, Fn work, uint32_t &checksum, int &failures)
{
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

int main()
{
    ioe_init();
    printf("mul-test: pure multiply benchmark, scale=%d\n", MUL_SCALE);
    int bad = 0;
    if (12345u * 6789u != 83810205u)
    {
        ++bad;
    }
    if (0x9E3779B9u * 0x85EBCA6Bu != 0xC10EDA53u)
    {
        ++bad;
    }
    printf("sanity: %s\n", bad == 0 ? "PASS" : "FAIL");

    uint32_t checksum = 0;
    int failures = bad;
    const uint64_t total_begin = uptime();

    measure(
        "serial", [] { return phase_serial(MUL_SCALE); }, checksum, failures);
    measure(
        "parallel", [] { return phase_parallel(MUL_SCALE); }, checksum, failures);
    measure(
        "bigmul", [] { return phase_bigmul(MUL_SCALE); }, checksum, failures);
    measure(
        "fib", [] { return phase_fib(MUL_SCALE); }, checksum, failures);
    measure(
        "matmul", [] { return phase_matmul(MUL_SCALE); }, checksum, failures);

    const uint64_t total_us = uptime() - total_begin;

    printf("total: %u cycles\n", total_cycles);
    printf("checksum=0x%08x failures=%d\n", checksum, failures);
    if (failures == 0)
    {
        printf("MUL TEST DONE\n");
    }
    else
    {
        printf("MUL TEST DONE (CHECK FAIL)\n");
    }
    printf("Scored time: %s ms\n", format_time(scored_us));
    printf("Total  time: %s ms\n", format_time(total_us));
    return failures == 0 ? 0 : 1;
}
