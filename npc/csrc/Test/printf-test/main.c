#include <am.h>
#include <errno.h>
#include <klib.h>
#include <limits.h>
#include <stdint.h>

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
static int failures;
static char buf[192];

#define CHECK(cond) \
  do { \
    if (!(cond)) { \
      failures++; \
      printf("printf-test check failed at line %d\n", __LINE__); \
    } \
  } while (0)

static void check_text_and_length(int len, const char *expect) {
  CHECK(strcmp(buf, expect) == 0);
  CHECK(len == (int)strlen(expect));
}
#endif

int main(void) {
#if defined(__ISA_NATIVE__) && !defined(__NATIVE_USE_KLIB__)
  printf("printf-test skipped: native libc is active\n");
  return 0;
#else
  int len = snprintf(buf, sizeof(buf), "%p|%u",
                     (void *)(uintptr_t)0x1234, 77u);
  check_text_and_length(len, "0x1234|77");

  len = snprintf(buf, sizeof(buf), "%lld|%llu|%llx",
                 LLONG_MIN, ULLONG_MAX, 0xfedcba9876543210ULL);
  check_text_and_length(
      len,
      "-9223372036854775808|18446744073709551615|fedcba9876543210");

  len = snprintf(buf, sizeof(buf), "%zu|%zd|%zx|%zX",
                 (size_t)128, (ptrdiff_t)-12345,
                 (size_t)0x89abcdefu, (size_t)0xabcdefu);
  check_text_and_length(len, "128|-12345|89abcdef|ABCDEF");

  len = snprintf(buf, sizeof(buf), "%X|%o", 0xdeadbeefu, 012345670u);
  check_text_and_length(len, "DEADBEEF|12345670");

  len = snprintf(buf, sizeof(buf), "%020p|%08lld|%05llu|%08llx|%08X|%06o|%06zu",
                 (void *)(uintptr_t)0x1234, -42LL, 42ULL, 0xabcULL,
                 0x1abcu, 0755u, (size_t)42);
  check_text_and_length(
      len,
      "0x000000000000001234|-0000042|00042|00000abc|00001ABC|000755|000042");

  len = snprintf(buf, sizeof(buf), "%p", (void *)0);
  check_text_and_length(len, "0x0");

  static const char raw[5] = {'A', 'B', 'C', 'D', 'E'};
  len = snprintf(buf, sizeof(buf), "|%.4s|%.16s|%.0s|",
                 raw, "abc", "ignored");
  check_text_and_length(len, "|ABCD|abc||");

  len = snprintf(buf, sizeof(buf), "|%.*s|%.*s|",
                 3, "abcdef", -1, "xy");
  check_text_and_length(len, "|abc|xy|");

  len = snprintf(buf, sizeof(buf), "|%-16s|%*s|%*s|",
                 "abc", 6, "xy", -6, "xy");
  check_text_and_length(len, "|abc             |    xy|xy    |");

  len = snprintf(buf, sizeof(buf), "|%*.*s|%*.*s|",
                 8, 3, "abcdef", -8, 3, "abcdef");
  check_text_and_length(len, "|     abc|abc     |");

  char tiny[5];
  len = snprintf(tiny, sizeof(tiny), "%*.*s", 8, 6, "abcdefghi");
  CHECK(len == 8);
  CHECK(strcmp(tiny, "  ab") == 0);

  len = snprintf(buf, sizeof(buf), "%#x|%#X|%#o|%#x|%#o",
                 0x2au, 0x2au, 10u, 0u, 0u);
  check_text_and_length(len, "0x2a|0X2A|012|0|0");

  len = snprintf(buf, sizeof(buf), "%+d|%+d|% d|% d|%+ d",
                 42, -42, 42, -42, 42);
  check_text_and_length(len, "+42|-42| 42|-42|+42");

  len = snprintf(buf, sizeof(buf), "%#08x|%-#8x|%+06d|% 06d|%-6d",
                 0x2au, 0x2au, 42, 42, 42);
  check_text_and_length(
      len, "0x00002a|0x2a    |+00042| 00042|42    ");

  len = snprintf(buf, sizeof(buf), "%.0d|%.0x|%#.0o|%08.3d|%#.3o",
                 0, 0u, 0u, 12, 8u);
  check_text_and_length(len, "||0|     012|010");

  len = snprintf(buf, sizeof(buf), "%hhd|%hhi|%hhu|%hhx|%hhX|%hho",
                 (int)SCHAR_MIN, (int)SCHAR_MAX, (int)UCHAR_MAX,
                 0xab, 0xab, (int)UCHAR_MAX);
  check_text_and_length(len, "-128|127|255|ab|AB|377");

  len = snprintf(buf, sizeof(buf), "%hd|%hi|%hu|%hx|%hX|%ho",
                 (int)SHRT_MIN, (int)SHRT_MAX, (int)USHRT_MAX,
                 0xabcd, 0xabcd, (int)USHRT_MAX);
  check_text_and_length(len, "-32768|32767|65535|abcd|ABCD|177777");

  len = snprintf(buf, sizeof(buf), "%hhd|%hhu|%hd|%hu",
                 130, 300, 65535, 65537);
  check_text_and_length(len, "-126|44|-1|1");

  ptrdiff_t td = (ptrdiff_t)-1234567;
  len = snprintf(buf, sizeof(buf), "%td|%ti|%tu|%tx|%tX|%to",
                 td, td, (size_t)4000000000u, (size_t)0x89abcdefu,
                 (size_t)0x89abcdefu, (size_t)0755u);
  check_text_and_length(
      len, "-1234567|-1234567|4000000000|89abcdef|89ABCDEF|755");

  intmax_t jmin = INTMAX_MIN;
  intmax_t jmax = INTMAX_MAX;
  uintmax_t jumax = UINTMAX_MAX;
  len = snprintf(buf, sizeof(buf), "%jd|%ji|%ju|%jx|%jX|%jo",
                 jmin, jmax, jumax, (uintmax_t)0xfedcba9876543210ULL,
                 (uintmax_t)0xabcdefu, (uintmax_t)0755u);
  check_text_and_length(
      len,
      "-9223372036854775808|9223372036854775807|18446744073709551615|fedcba9876543210|ABCDEF|755");

  len = snprintf(buf, sizeof(buf), "%+06hhd|%#06hhx|%+06td|%#08jX",
                 5, 0xab, (ptrdiff_t)42, (uintmax_t)0xabcu);
  check_text_and_length(len, "+00005|0x00ab|+00042|0X000ABC");

  // 无法获知未知格式对应的参数类型，因此必须立即失败，不能继续错读参数。
  char extreme[4];
  errno = EFAULT;
  len = snprintf(NULL, 0, "%*s", INT_MAX, "");
  CHECK(len == INT_MAX);
  CHECK(errno == EFAULT);
  len = snprintf(extreme, sizeof(extreme), "%*s", INT_MAX, "");
  CHECK(len == INT_MAX);
  CHECK(strcmp(extreme, "   ") == 0);

  errno = 0;
  len = snprintf(extreme, sizeof(extreme), "%*sX", INT_MAX, "");
  CHECK(len == -1);
  CHECK(errno == EOVERFLOW);
  CHECK(strcmp(extreme, "   ") == 0);

  errno = 0;
  len = snprintf(extreme, sizeof(extreme), "x%*s", INT_MAX, "");
  CHECK(len == -1);
  CHECK(errno == EOVERFLOW);
  CHECK(strcmp(extreme, "x") == 0);

  errno = EFAULT;
  len = snprintf(extreme, sizeof(extreme), "%.*d", INT_MAX, 0);
  CHECK(len == INT_MAX);
  CHECK(errno == EFAULT);
  CHECK(strcmp(extreme, "000") == 0);

  errno = 0;
  len = snprintf(extreme, sizeof(extreme), "%+.*d", INT_MAX, 0);
  CHECK(len == -1);
  CHECK(errno == EOVERFLOW);
  CHECK(extreme[0] == '\0');

  errno = 0;
  len = snprintf(extreme, sizeof(extreme), "%#.*x", INT_MAX, 1u);
  CHECK(len == -1);
  CHECK(errno == EOVERFLOW);
  CHECK(extreme[0] == '\0');

  errno = EFAULT;
  len = snprintf(extreme, sizeof(extreme), "%s", "ok");
  CHECK(len == 2 && strcmp(extreme, "ok") == 0);
  CHECK(errno == EFAULT);

  const char unknown_format[] = "before:%q after:%d";
  len = snprintf(buf, sizeof(buf), unknown_format, 111, 222);
  CHECK(len == -1);
  CHECK(strcmp(buf, "before:") == 0);

  // 已知长度修饰符用于不支持的转换组合时，也必须在va_arg之前停止。
  const char invalid_length[] = "bad:%ls after";
  len = snprintf(buf, sizeof(buf), invalid_length);
  CHECK(len == -1);
  CHECK(strcmp(buf, "bad:") == 0);

  const char incomplete_format[] = "tail:%";
  len = snprintf(buf, sizeof(buf), incomplete_format);
  CHECK(len == -1);
  CHECK(strcmp(buf, "tail:") == 0);

  const char width_overflow_format[] = "wide:%2147483648d";
  errno = 0;
  len = snprintf(buf, sizeof(buf), width_overflow_format, 1);
  CHECK(len == -1);
  CHECK(errno == EOVERFLOW);
  CHECK(strcmp(buf, "wide:") == 0);

  const char dynamic_width_overflow_format[] = "wide:%*d";
  errno = 0;
  len = snprintf(buf, sizeof(buf), dynamic_width_overflow_format, INT_MIN, 1);
  CHECK(len == -1);
  CHECK(errno == EOVERFLOW);
  CHECK(strcmp(buf, "wide:") == 0);

  const char precision_overflow_format[] = "prec:%.2147483648d";
  errno = 0;
  len = snprintf(buf, sizeof(buf), precision_overflow_format, 1);
  CHECK(len == -1);
  CHECK(errno == EOVERFLOW);
  CHECK(strcmp(buf, "prec:") == 0);

  // 格式错误不能污染下一次调用。
  len = snprintf(buf, sizeof(buf), "%zu", (size_t)42);
  check_text_and_length(len, "42");

  printf(failures == 0 ? "PRINTF TEST PASS\n"
                       : "PRINTF TEST FAIL, failures=%d\n",
         failures);
  halt(failures == 0 ? 0 : 1);
  return failures == 0 ? 0 : 1;
#endif
}
