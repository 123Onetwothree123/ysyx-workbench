/* GCC 硬浮点源程序回归：rv64imafd/lp64d 与 rv32imafd/ilp32d。
 * 只使用 freestanding 环境，返回值 0 表示全部通过。
 */
typedef unsigned long long u64;
typedef unsigned int u32;

static u64 dbits(double d)
{
  union { double d; u64 u; } v;
  v.d = d;
  return v.u;
}

static u32 fbits(float f)
{
  union { float f; u32 u; } v;
  v.f = f;
  return v.u;
}

static void fflags_clear(void)
{
  __asm__ volatile("csrw fflags, zero" ::: "memory");
}

static u32 fflags_read(void)
{
  u32 v;
  __asm__ volatile("csrr %0, fflags" : "=r"(v) : : "memory");
  return v;
}

/* 在同一个 asm 块内设置 frm、执行 fadd.d 并恢复 RNE，确保编译器
 * 不会把恢复 frm 的 CSR 写重排到浮点运算之前。 */
static double add_with_rm(double a, double b, u32 mode)
{
  double r;
  __asm__ volatile("csrw frm, %1\n\t"
                   "fadd.d %0, %2, %3\n\t"
                   "csrw frm, zero"
                   : "=f"(r) : "r"(mode), "f"(a), "f"(b) : "memory");
  return r;
}

/* 防止编译器把常量运算折叠成编译期结果。 */
volatile double g_a = 1.5;
volatile double g_b = 2.5;
volatile double g_quarter = 0.25;
volatile float g_fa = 1.5f;
volatile float g_fb = 2.5f;
volatile double g_three7 = 3.7;
volatile double g_nine = 9.0;
volatile int g_neg7 = -7;

int main(void)
{
  double a = g_a, b = g_b;
  double z = a + b;
  if (dbits(z) != 0x4010000000000000ULL) return 1;

  z = a * b;
  if (dbits(z) != 0x400e000000000000ULL) return 2;

  z = a / b; /* 0.6 的最近双精度值 */
  if (dbits(z) != 0x3fe3333333333333ULL) return 3;

  z = __builtin_sqrt(g_nine);
  if (dbits(z) != 0x4008000000000000ULL) return 4;

  /* 融合乘加：a=1+2^-52, b=1-2^-53 的乘积与 1 相差 2^-53。
   * 非融合实现会先舍入为 1.0，再减 1 得到 0。 */
  {
    union { double d; u64 u; } ua = { .u = 0x3ff0000000000001ULL };
    union { double d; u64 u; } ub = { .u = 0x3fefffffffffffffULL };
    volatile double va = ua.d, vb = ub.d;
    double fused = __builtin_fma(va, vb, -1.0);
    if (fused == 0.0) return 5;
  }

  float fa = g_fa, fb = g_fb;
  float fz = fa + fb;
  if (fbits(fz) != 0x40800000u) return 6;
  fz = fa * fb;
  if (fbits(fz) != 0x40700000u) return 7;

  int i = (int)g_three7; /* FCVT.W.D, RTZ */
  if (i != 3) return 8;
  double dn = (double)g_neg7;
  if (dbits(dn) != 0xc01c000000000000ULL) return 9;
  float fdn = (float)g_neg7;
  if (fbits(fdn) != 0xc0e00000u) return 10;

  /* 单精度 4.0f -> 双精度 4.0 */
  double dfromf = (double)fz; /* fz = 3.75f */
  if (dbits(dfromf) != 0x400e000000000000ULL) return 11;
  float ffromd = (float)b; /* 2.5 精确 */
  if (fbits(ffromd) != 0x40200000u) return 12;

  /* qNaN 传播：RISC-V 输出 canonical NaN，不置 NV */
  {
    union { double d; u64 u; } q = { .u = 0x7ff8000000000001ULL };
    volatile double vq = q.d;
    fflags_clear();
    double r = vq + a;
    if (dbits(r) != 0x7ff8000000000000ULL) return 13;
    if (fflags_read() != 0) return 14;
  }

  /* sNaN 传播：canonical NaN + NV */
  {
    union { double d; u64 u; } s = { .u = 0x7ff0000000000001ULL };
    volatile double vs = s.d;
    fflags_clear();
    double r = vs + a;
    if (dbits(r) != 0x7ff8000000000000ULL) return 15;
    if (fflags_read() != 0x10) return 16;
  }

  /* 动态舍入模式 RUP：1.0 + 2^-53 -> 1+2^-52，并置 NX */
  {
    volatile double one = 1.0;
    volatile double half = 0x1p-53;
    fflags_clear();
    double r = add_with_rm(one, half, 3);
    if (dbits(r) != 0x3ff0000000000001ULL) return 17;
    if ((fflags_read() & 1) == 0) return 18;
  }

  /* 执行过 FP 后 mstatus.FS 必须为 Dirty。 */
  {
    unsigned long ms;
    __asm__ volatile("csrr %0, mstatus" : "=r"(ms));
    if (((ms >> 13) & 3) != 3) return 19;
  }

  return 0;
}
