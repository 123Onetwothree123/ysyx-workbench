/*
 * RV64D GCC 硬浮点源程序。
 *
 * 必须使用 -march=rv64imafd -mabi=lp64d 构建；本文件故意不包含任何
 * 软件浮点回退。测试脚本会检查 ELF 的 hard-float ABI 标志、反汇编中的
 * F/D 指令，并拒绝 __adddf3 等 libgcc 软浮点符号。
 *
 * 这里的运算都经过 volatile 全局变量，避免常量折叠；同时用内联汇编
 * 覆盖 C 编译器不一定会生成的 FMA/FCLASS/fcsr 指令。
 */
typedef unsigned long u64;
typedef unsigned int u32;

union double_bits {
    double d;
    u64 u;
};

union float_bits {
    float f;
    u32 u;
};

static volatile double g_a = 1.5;
static volatile double g_b = 2.25;
static volatile double g_c = 1.0;
static volatile double g_one = 1.0;
static volatile double g_three = 3.0;
static volatile double g_3_75 = 3.75;
static volatile float g_fa = 1.5f;
static volatile float g_fb = 2.25f;
static volatile float g_f_3_75 = 3.75f;

static union double_bits d_bits(double value)
{
    union double_bits bits;
    bits.d = value;
    return bits;
}

static union float_bits f_bits(float value)
{
    union float_bits bits;
    bits.f = value;
    return bits;
}

static int expect_d(double value, u64 expected, int code)
{
    return d_bits(value).u == expected ? 0 : code;
}

static int expect_f(float value, u32 expected, int code)
{
    return f_bits(value).u == expected ? 0 : code;
}

int fpu_c_main(void)
{
    double sum = g_a + g_b;
    double diff = g_a - g_b;
    double prod = g_a * g_b;
    double quot = g_a / g_b;
    float fsum = g_fa + g_fb;
    float fprod = g_fa * g_fb;

    if (expect_d(sum, 0x400e000000000000UL, 1))
        return 1;
    if (expect_d(diff, 0xbfe8000000000000UL, 2))
        return 2;
    if (expect_d(prod, 0x400b000000000000UL, 3))
        return 3;
    if (expect_d(quot, 0x3fe5555555555555UL, 4))
        return 4;
    if (expect_f(fsum, 0x40700000U, 5))
        return 5;
    if (expect_f(fprod, 0x40580000U, 6))
        return 6;

    if (!(g_a < g_b))
        return 7;
    if (g_a == g_b)
        return 7;
    if (!(g_b >= g_a))
        return 7;

    /* C 的浮点->整数转换按 RTZ。 */
    int iv = (int)g_3_75;
    if (iv != 3)
        return 8;
    unsigned int uiv = (unsigned int)g_f_3_75;
    if (uiv != 3U)
        return 8;
    long lv = (long)g_3_75;
    if (lv != 3)
        return 8;
    double back = (double)(-7);
    if (expect_d(back, 0xc01c000000000000UL, 9))
        return 9;

    /* 内联 FMA，避免依赖 libm 的 fma()。 */
    double fused;
    __asm__ volatile("fmadd.d %0, %1, %2, %3"
                     : "=f"(fused)
                     : "f"(g_a), "f"(g_b), "f"(g_c));
    if (expect_d(fused, 0x4011800000000000UL, 10))
        return 10;

    /* FCLASS：+inf 的类别位是 0x004。 */
    union double_bits inf_bits;
    inf_bits.u = 0x7ff0000000000000UL;
    u64 klass;
    __asm__ volatile("fclass.d %0, %1" : "=r"(klass) : "f"(inf_bits.d));
    if (klass != 0x080UL)
        return 11;

    /* 1.0 / 3.0 应产生 NX(bit0)。 */
    double third = g_one / g_three;
    u64 fcsr;
    __asm__ volatile("frcsr %0" : "=r"(fcsr));
    if ((fcsr & 0x1fUL) != 0x1UL)
        return 12;
    if (expect_d(third, 0x3fd5555555555555UL, 12))
        return 12;

    /* qNaN + 1.0 应规范化为正 canonical NaN，且不置 NV。 */
    union double_bits nan_bits;
    nan_bits.u = 0x7ff8000000000000UL;
    volatile double nan_value = nan_bits.d;
    double nan_sum = nan_value + g_one;
    if (expect_d(nan_sum, 0x7ff8000000000000UL, 13))
        return 13;

    return 0;
}
