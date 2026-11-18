/* GCC 硬浮点 ABI 回归：用 -march=rv64imafd -mabi=lp64d 编译，确认
 * 编译器生成的 F/D 指令、调用约定和转换在 NPC 上可执行。
 * 不使用任何 libc/libgcc 软浮点辅助函数。 */

volatile float fsrc[4] = {1.5f, -2.25f, 3.75f, -0.5f};
volatile double dsrc[4] = {1.5, -2.25, 3.75, -0.5};
volatile int isink;
volatile unsigned usink;
volatile float fsink;
volatile double dsink;

int fpu_c_test(void)
{
    float a = fsrc[0];
    float b = fsrc[1];
    float c = a + b;
    if (c != -0.75f)
        return 1;
    c = a - b;
    if (c != 3.75f)
        return 2;
    c = a * b;
    if (c != -3.375f)
        return 3;
    c = a / fsrc[2];
    if (c != 0.4f)
        return 4;
    if (!(a < fsrc[2]) || !(a <= fsrc[2]) || (a == fsrc[2]))
        return 5;

    int i = (int)fsrc[2];          /* C cast 向零截断：3.75 -> 3 */
    if (i != 3)
        return 6;
    isink = (int)fsrc[1];          /* -2.25 -> -2 */
    if (isink != -2)
        return 7;
    unsigned u = (unsigned)fsrc[2]; /* fcvt.wu.s：3.75 -> 3 */
    if (u != 3u)
        return 8;
    fsink = (float)i;              /* fcvt.s.w */
    if (fsink != 3.0f)
        return 9;
    fsink = (float)u;              /* fcvt.s.wu */
    if (fsink != 3.0f)
        return 10;

#ifdef FPU_TEST_DOUBLE
    double da = dsrc[0];
    double db = dsrc[1];
    double dc = da + db;
    if (dc != -0.75)
        return 11;
    dc = da * db;
    if (dc != -3.375)
        return 12;
    dc = da / dsrc[2];
    if (dc != 0.4)
        return 13;
    if (!(da < dsrc[2]) || !(da <= dsrc[2]) || (da == dsrc[2]))
        return 14;

    long l = (long)dsrc[2];        /* C cast 向零截断：3.75 -> 3 */
    if (l != 3)
        return 15;
    dsink = (double)l;             /* fcvt.d.l */
    if (dsink != 3.0)
        return 16;
    double d2 = (double)a;         /* fcvt.d.s */
    if (d2 != 1.5)
        return 17;
    float f2 = (float)da;          /* fcvt.s.d */
    if (f2 != 1.5f)
        return 18;
    dsink = da * 2.0 + db;         /* fmadd/fmul+fadd 组合 */
    if (dsink != 0.75)
        return 19;
    usink = (unsigned)dsrc[2];     /* fcvt.wu.d */
    if (usink != 3u)
        return 20;
#endif
    return 0;
}
