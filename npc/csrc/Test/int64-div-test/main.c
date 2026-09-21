#include <am.h>
#include <klib.h>
#include <limits.h>

extern long long __divdi3(long long a, long long b);
extern long long __moddi3(long long a, long long b);
extern long long __divmoddi4(long long a, long long b, long long *rem);

static int failures;

static void check_case(
    long long a,
    long long b,
    long long expected_q,
    long long expected_r)
{
  volatile long long dividend = a;
  volatile long long divisor = b;
  long long remainder = 0;
  long long quotient = __divdi3(dividend, divisor);
  long long modulo = __moddi3(dividend, divisor);
  long long combined = __divmoddi4(dividend, divisor, &remainder);
  if (quotient != expected_q || modulo != expected_r ||
      combined != expected_q || remainder != expected_r)
  {
    failures++;
    printf("int64 case failed: %lld / %lld, q=%lld/%lld r=%lld/%lld\n",
           a, b, quotient, combined, modulo, remainder);
  }
}

int main(void)
{
  check_case(LLONG_MIN, 1, LLONG_MIN, 0);
  check_case(LLONG_MIN, -1, LLONG_MIN, 0);
  check_case(LLONG_MIN, 2, -4611686018427387904LL, 0);
  check_case(LLONG_MIN, -2, 4611686018427387904LL, 0);
  check_case(LLONG_MIN, 3, -3074457345618258602LL, -2);
  check_case(LLONG_MIN, -3, 3074457345618258602LL, -2);
  check_case(LLONG_MIN + 1, 3, -3074457345618258602LL, -1);
  check_case(LLONG_MIN + 1, -3, 3074457345618258602LL, -1);
  check_case(LLONG_MAX, -1, -LLONG_MAX, 0);
  check_case(LLONG_MAX, 2, 4611686018427387903LL, 1);
  check_case(-1, LLONG_MIN, 0, -1);
  check_case(1, LLONG_MIN, 0, 1);
  check_case(LLONG_MIN, LLONG_MIN, 1, 0);
  check_case(LLONG_MAX, LLONG_MIN, 0, LLONG_MAX);

  printf(failures == 0 ? "INT64 DIV TEST PASS\n"
                       : "INT64 DIV TEST FAIL, failures=%d\n",
         failures);
  halt(failures == 0 ? 0 : 1);
  return failures == 0 ? 0 : 1;
}
