#include <am.h>
#include <errno.h>
#include <klib.h>
#include <limits.h>

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
static int failures;

#define CHECK(cond) \
  do { \
    if (!(cond)) { \
      failures++; \
      printf("strto-test check failed at line %d\n", __LINE__); \
    } \
  } while (0)

static void check_end(const char *input, const char *end, size_t offset) {
  CHECK(end == input + offset);
}
#endif

int main(void) {
#if defined(__ISA_NATIVE__) && !defined(__NATIVE_USE_KLIB__)
  printf("strto-test skipped: native libc is active\n");
  return 0;
#else
  const char *input = " \t\n\v\f\r-42q";
  char *end = NULL;
  errno = EFAULT;
  CHECK(strtol(input, &end, 10) == -42);
  check_end(input, end, 9);
  CHECK(errno == EFAULT);

  input = "+42!";
  errno = EFAULT;
  CHECK(strtoul(input, &end, 10) == 42ul);
  check_end(input, end, 3);
  CHECK(errno == EFAULT);

  input = "077!";
  CHECK(strtol(input, &end, 0) == 63);
  check_end(input, end, 3);
  input = "0x2A!";
  CHECK(strtol(input, &end, 0) == 42);
  check_end(input, end, 4);
  input = "0X2a!";
  CHECK(strtoul(input, &end, 16) == 42ul);
  check_end(input, end, 4);
  input = "08";
  CHECK(strtol(input, &end, 0) == 0);
  check_end(input, end, 1);
  input = "0x";
  CHECK(strtol(input, &end, 0) == 0);
  check_end(input, end, 1);
  input = "-0x";
  CHECK(strtol(input, &end, 0) == 0);
  check_end(input, end, 2);
  input = "0b101";
  CHECK(strtol(input, &end, 0) == 0);
  check_end(input, end, 1);

  input = "10102";
  CHECK(strtol(input, &end, 2) == 10);
  check_end(input, end, 4);
  input = "178";
  CHECK(strtoul(input, &end, 8) == 15ul);
  check_end(input, end, 2);
  input = "7fG";
  CHECK(strtoll(input, &end, 16) == 127ll);
  check_end(input, end, 2);
  input = "zZ!";
  CHECK(strtoull(input, &end, 36) == 1295ull);
  check_end(input, end, 2);

  input = " \t-x";
  errno = EFAULT;
  CHECK(strtol(input, &end, 10) == 0);
  CHECK(end == input);
  CHECK(errno == EFAULT);
  CHECK(strtoul(input, &end, 10) == 0ul);
  CHECK(end == input);
  CHECK(strtoll(input, &end, 10) == 0ll);
  CHECK(end == input);
  CHECK(strtoull(input, &end, 10) == 0ull);
  CHECK(end == input);
  CHECK(errno == EFAULT);

  input = "123";
  end = (char *)1;
  errno = EFAULT;
  CHECK(strtol(input, &end, 1) == 0);
  CHECK(end == input);
  CHECK(errno == EINVAL);
  errno = EFAULT;
  CHECK(strtoul(input, &end, 37) == 0ul);
  CHECK(end == input);
  CHECK(errno == EINVAL);
  errno = EFAULT;
  CHECK(strtoll(input, &end, -2) == 0ll);
  CHECK(end == input);
  CHECK(errno == EINVAL);
  errno = EFAULT;
  CHECK(strtoull(input, &end, 99) == 0ull);
  CHECK(end == input);
  CHECK(errno == EINVAL);

#if LONG_MAX == 2147483647L
  input = "2147483647!";
  errno = EFAULT;
  CHECK(strtol(input, &end, 10) == LONG_MAX);
  check_end(input, end, 10);
  CHECK(errno == EFAULT);
  input = "-2147483648!";
  CHECK(strtol(input, &end, 10) == LONG_MIN);
  check_end(input, end, 11);
  CHECK(errno == EFAULT);
  input = "2147483648tail";
  errno = 0;
  CHECK(strtol(input, &end, 10) == LONG_MAX);
  check_end(input, end, 10);
  CHECK(errno == ERANGE);
  input = "-2147483649tail";
  errno = 0;
  CHECK(strtol(input, &end, 10) == LONG_MIN);
  check_end(input, end, 11);
  CHECK(errno == ERANGE);

  input = "4294967295!";
  errno = EFAULT;
  CHECK(strtoul(input, &end, 10) == ULONG_MAX);
  check_end(input, end, 10);
  CHECK(errno == EFAULT);
  input = "4294967296tail";
  errno = 0;
  CHECK(strtoul(input, &end, 10) == ULONG_MAX);
  check_end(input, end, 10);
  CHECK(errno == ERANGE);
#endif

  input = "9223372036854775807!";
  errno = EFAULT;
  CHECK(strtoll(input, &end, 10) == LLONG_MAX);
  check_end(input, end, 19);
  CHECK(errno == EFAULT);
  input = "-9223372036854775808!";
  CHECK(strtoll(input, &end, 10) == LLONG_MIN);
  check_end(input, end, 20);
  CHECK(errno == EFAULT);
  input = "9223372036854775808tail";
  errno = 0;
  CHECK(strtoll(input, &end, 10) == LLONG_MAX);
  check_end(input, end, 19);
  CHECK(errno == ERANGE);
  input = "-9223372036854775809tail";
  errno = 0;
  CHECK(strtoll(input, &end, 10) == LLONG_MIN);
  check_end(input, end, 20);
  CHECK(errno == ERANGE);

  input = "18446744073709551615!";
  errno = EFAULT;
  CHECK(strtoull(input, &end, 10) == ULLONG_MAX);
  check_end(input, end, 20);
  CHECK(errno == EFAULT);
  input = "18446744073709551616tail";
  errno = 0;
  CHECK(strtoull(input, &end, 10) == ULLONG_MAX);
  check_end(input, end, 20);
  CHECK(errno == ERANGE);

  errno = EFAULT;
  CHECK(strtoul("-1", NULL, 10) == ULONG_MAX);
  CHECK(errno == EFAULT);
  errno = EFAULT;
  CHECK(strtoull("-1", NULL, 10) == ULLONG_MAX);
  CHECK(errno == EFAULT);
  errno = EFAULT;
  CHECK(strtoull("-18446744073709551615", NULL, 10) == 1ull);
  CHECK(errno == EFAULT);
  errno = 0;
  CHECK(strtoull("-18446744073709551616", NULL, 10) == ULLONG_MAX);
  CHECK(errno == ERANGE);

  CHECK(atoi(" \t-42rest") == -42);

  printf(failures == 0 ? "STRTO TEST PASS\n"
                       : "STRTO TEST FAIL, failures=%d\n",
         failures);
  halt(failures == 0 ? 0 : 1);
  return failures == 0 ? 0 : 1;
#endif
}
