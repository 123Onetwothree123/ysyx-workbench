#include <stdint.h>
#include <stdio.h>
#include <unistd.h>

/*
 * RV64GC 用户态浮点探针。
 *
 * 该程序只在 lp64d 的 RV64GC 目标中作为第三个用户进程装入 Nanos-lite。
 * 被 volatile 修饰的 float/double 会阻止编译器把运算折叠成整数指令，
 * 因此运行日志中的结果必须由用户态的 F/D 指令真正算出来：
 *   fdiv.d / fmul.d / fadd.d / fsub.d / fcvt.l.d，以及 F 格式的
 *   fmul.s / fadd.s。
 * 这里不使用 printf 的 %f（Navy 的 newlib 关闭了浮点格式化），而是
 * 把 float/double 的原始位模式和放大后的整数打印出来。
 */
int main(void) {
  volatile double a = 3.0;
  volatile double b = 7.0;
  volatile double acc = a / b;       /* fdiv.d */
  volatile float facc = 1.25f;       /* F 格式探针 */
  volatile unsigned long iter = 0;

  write(1, "fp-test: RV64GC user-mode F/D probe start\n", 42);

  for (iter = 1; iter <= 200; iter++) {
    /* 乘加链，保证每轮都产生新的 double 结果。 */
    acc = acc * 1.0000001 + 0.0000001; /* fmul.d + fadd.d */
    acc = acc - 0.0000001;             /* fsub.d */
    facc = facc * 1.5f + 0.25f;        /* fmul.s + fadd.s */
  }

  union {
    double d;
    uint64_t u;
  } bits;
  union {
    float f;
    uint32_t u;
  } fbits;
  bits.d = acc;
  fbits.f = facc;

  long long scaled = (long long)(acc * 1000000.0); /* fcvt.l.d */
  printf("fp-test[%d]: iter=%lu acc_bits=0x%llx scaled_1e6=%lld f_bits=0x%x\n",
         getpid(), iter, (unsigned long long)bits.u, scaled, fbits.u);

  /* 持续运行，让时钟中断还能在本进程内抢占并触发上下文切换。 */
  volatile double x = 1.0;
  unsigned long round = 1;
  for (;;) {
    x = x * 1.000001 + 0.25;
    if ((round % 50000) == 0) {
      bits.d = x;
      printf("fp-test[%d]: round %lu bits=0x%llx\n", getpid(), round,
             (unsigned long long)bits.u);
    }
    round++;
  }
  return 0;
}
