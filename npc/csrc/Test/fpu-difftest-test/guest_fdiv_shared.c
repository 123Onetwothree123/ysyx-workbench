/*
 * 共享 FDIV/FSQRT 的 NEMU DiffTest 压力镜像。
 *
 * 该程序只用整数循环生成操作数，但在内联汇编中执行大量 F/D 除法与开方，
 * 每笔都把结果和 fcsr 读回整数寄存器。NPC 的逐退休 DiffTest 会把每个
 * GPR 与 NEMU REF 比较，因此任何一位结果或 fflags 差异都会在第一次
 * 分叉时暴露。覆盖：
 *   - F/D 除法与开方的边界值（±0、±Inf、qNaN/sNaN、最小正规数、
 *     最小次正规数、最大有限数、1/3 等）；
 *   - 全部五种舍入模式；
 *   - 伪随机 32/64 位操作数。
 *
 * 期望值由 NEMU 参考模型给出，不在镜像里复制第二套浮点实现。
 */
typedef unsigned long u64;
typedef unsigned int u32;

static u64 rng_state = 0x123456789abcdef0UL;

static u32 rng32(void)
{
    rng_state ^= rng_state << 13;
    rng_state ^= rng_state >> 7;
    rng_state ^= rng_state << 17;
    return (u32)rng_state;
}

static u64 rng64(void)
{
    u64 hi = rng32();
    u64 lo = rng32();
    return (hi << 32) | lo;
}

static u32 fdiv_s(u32 a, u32 b, u32 rm, u32 *flags)
{
    u32 result;
    u32 fcsr;
    __asm__ volatile(
        "csrw frm, %[rm]\n\t"
        "csrw fflags, zero\n\t"
        "fmv.w.x ft0, %[a]\n\t"
        "fmv.w.x ft1, %[b]\n\t"
        "fdiv.s ft2, ft0, ft1\n\t"
        "fmv.x.w %[r], ft2\n\t"
        "csrr %[f], fcsr\n\t"
        : [r] "=r"(result), [f] "=r"(fcsr)
        : [a] "r"(a), [b] "r"(b), [rm] "r"(rm)
        : "ft0", "ft1", "ft2");
    *flags = fcsr & 0x1fU;
    return result;
}

static u32 fsqrt_s(u32 a, u32 rm, u32 *flags)
{
    u32 result;
    u32 fcsr;
    __asm__ volatile(
        "csrw frm, %[rm]\n\t"
        "csrw fflags, zero\n\t"
        "fmv.w.x ft0, %[a]\n\t"
        "fsqrt.s ft2, ft0\n\t"
        "fmv.x.w %[r], ft2\n\t"
        "csrr %[f], fcsr\n\t"
        : [r] "=r"(result), [f] "=r"(fcsr)
        : [a] "r"(a), [rm] "r"(rm)
        : "ft0", "ft2");
    *flags = fcsr & 0x1fU;
    return result;
}

static u64 fdiv_d(u64 a, u64 b, u32 rm, u32 *flags)
{
    u64 result;
    u32 fcsr;
    __asm__ volatile(
        "csrw frm, %[rm]\n\t"
        "csrw fflags, zero\n\t"
        "fmv.d.x ft0, %[a]\n\t"
        "fmv.d.x ft1, %[b]\n\t"
        "fdiv.d ft2, ft0, ft1\n\t"
        "fmv.x.d %[r], ft2\n\t"
        "csrr %[f], fcsr\n\t"
        : [r] "=r"(result), [f] "=r"(fcsr)
        : [a] "r"(a), [b] "r"(b), [rm] "r"(rm)
        : "ft0", "ft1", "ft2");
    *flags = fcsr & 0x1fU;
    return result;
}

static u64 fsqrt_d(u64 a, u32 rm, u32 *flags)
{
    u64 result;
    u32 fcsr;
    __asm__ volatile(
        "csrw frm, %[rm]\n\t"
        "csrw fflags, zero\n\t"
        "fmv.d.x ft0, %[a]\n\t"
        "fsqrt.d ft2, ft0\n\t"
        "fmv.x.d %[r], ft2\n\t"
        "csrr %[f], fcsr\n\t"
        : [r] "=r"(result), [f] "=r"(fcsr)
        : [a] "r"(a), [rm] "r"(rm)
        : "ft0", "ft2");
    *flags = fcsr & 0x1fU;
    return result;
}

static const u32 edges32[] = {
    0x00000000U, 0x80000000U, 0x7f800000U, 0xff800000U,
    0x7fc00000U, 0x7f800001U, 0x00800000U, 0x00000001U,
    0x7f7fffffU, 0x3f800000U, 0x40400000U, 0x3f000000U,
    0x4b000000U, 0x00000002U, 0x7f000000U, 0x00800001U
};

static const u64 edges64[] = {
    0x0000000000000000UL, 0x8000000000000000UL,
    0x7ff0000000000000UL, 0xfff0000000000000UL,
    0x7ff8000000000000UL, 0x7ff0000000000001UL,
    0x0010000000000000UL, 0x0000000000000001UL,
    0x7fefffffffffffffUL, 0x3ff0000000000000UL,
    0x4008000000000000UL, 0x3fe0000000000000UL,
    0x4330000000000000UL, 0x0000000000000002UL,
    0x7fe0000000000000UL, 0x0010000000000001UL
};

static u32 acc32;
static u64 acc64;

static void run_f(void)
{
    u32 i, j, rm, flags, result;
    for (i = 0; i < sizeof(edges32) / sizeof(edges32[0]); i++) {
        for (j = 0; j < sizeof(edges32) / sizeof(edges32[0]); j++) {
            for (rm = 0; rm < 5; rm++) {
                result = fdiv_s(edges32[i], edges32[j], rm, &flags);
                acc32 ^= result ^ (flags << 8) ^ (u32)(i * 131 + j * 17 + rm);
            }
        }
        for (rm = 0; rm < 5; rm++) {
            result = fsqrt_s(edges32[i], rm, &flags);
            acc32 ^= result ^ (flags << 8) ^ (u32)(i * 29 + rm);
        }
    }
    for (i = 0; i < 600; i++) {
        u32 a = rng32();
        u32 b = rng32();
        u32 rm2 = rng32() % 5;
        result = fdiv_s(a, b, rm2, &flags);
        acc32 ^= result ^ (flags << 8) ^ a ^ b ^ rm2;
        result = fsqrt_s(a, rm2, &flags);
        acc32 ^= result ^ (flags << 8) ^ a;
    }
}

static void run_d(void)
{
    u32 i, j, rm, flags;
    u64 result;
    for (i = 0; i < sizeof(edges64) / sizeof(edges64[0]); i++) {
        for (j = 0; j < sizeof(edges64) / sizeof(edges64[0]); j++) {
            for (rm = 0; rm < 5; rm++) {
                result = fdiv_d(edges64[i], edges64[j], rm, &flags);
                acc64 ^= result ^ ((u64)flags << 32) ^
                    ((u64)i * 131 + (u64)j * 17 + rm);
            }
        }
        for (rm = 0; rm < 5; rm++) {
            result = fsqrt_d(edges64[i], rm, &flags);
            acc64 ^= result ^ ((u64)flags << 32) ^ ((u64)i * 29 + rm);
        }
    }
    for (i = 0; i < 300; i++) {
        u64 a = rng64();
        u64 b = rng64();
        u32 rm2 = rng32() % 5;
        result = fdiv_d(a, b, rm2, &flags);
        acc64 ^= result ^ ((u64)flags << 32) ^ a ^ b ^ rm2;
        result = fsqrt_d(a, rm2, &flags);
        acc64 ^= result ^ ((u64)flags << 32) ^ a;
    }
}

int fpu_c_main(void)
{
    acc32 = 0;
    acc64 = 0;
    run_f();
    run_d();
    /* 结果和 fcsr 已逐笔进入 GPR，由 DiffTest 与 NEMU 比对。 */
    return 0;
}
