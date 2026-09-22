#include <am.h>
#include <cassert>
#include <cerrno>
#include <cctype>
#include <clocale>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <cwchar>
#include <cwctype>
#include <klib.h>

#ifndef errno
#error "<cerrno> must define errno as a macro"
#endif

extern "C" int cpp_assert_reinclude_probe(void);

static int destruction_state;
static int local_constructor_count;

struct GlobalObject {
  int expected;
  int next;

  ~GlobalObject() {
    if (destruction_state != expected) halt(1);
    destruction_state = next;
    if (next == 2) std::fputs("C++ RUNTIME TEST PASS", stdout);
  }
};

static GlobalObject first = {1, 2};
static GlobalObject second = {0, 1};

struct LocalObject {
  LocalObject() { local_constructor_count++; }
};

static void construct_local_once() {
  static LocalObject local;
  (void)local;
}

int main() {
  construct_local_once();
  construct_local_once();
  if (local_constructor_count != 1 || !cpp_assert_reinclude_probe()) return 1;
  volatile double one = 1.0;
  volatile double negative_zero = -0.0;
  if (!std::isfinite(one) || std::fpclassify(one) != FP_NORMAL ||
      !std::signbit(negative_zero) ||
      !std::signbit(std::copysign(one, negative_zero))) return 1;

  if (!std::isfinite(1) || std::isnan(1) || std::fabs(-7) != 7.0 ||
      std::copysign(1, -1) != -1.0 || !std::isalpha('K') ||
      std::tolower('K') != 'k' || std::localeconv() == nullptr) return 1;

  volatile float float_value = -3.5f;
  volatile long double long_value = -0.0L;
  const long double positive_long = std::abs(-3.5L);
  if (std::fabs(float_value) != 3.5f || std::abs(float_value) != 3.5f ||
      std::abs(-3.5) != 3.5 || std::signbit(positive_long) ||
      !std::signbit(long_value) ||
      std::fpclassify(0x1p-149f) != FP_SUBNORMAL) return 1;

  int exponent = 0;
  if (std::frexp(8.0f, &exponent) != 0.5f || exponent != 4 ||
      std::scalbn(0.5f, 4) != 8.0f || std::sqrt(4.0) != 2.0) return 1;

  float integer_part = 0.0f;
  if (std::modf(-3.5f, &integer_part) != -0.5f ||
      integer_part != -3.0f ||
      !std::signbit(std::nextafter(0.0f, -1.0f))) return 1;

  std::div_t quotient = std::div(17, 5);
  char tokens[] = "kernel,libc";
  char *first_token = std::strtok(tokens, ",");
  char *second_token = std::strtok(nullptr, ",");
  std::time_t epoch = 0;
  const std::tm *calendar = std::gmtime(&epoch);
  if (std::abs(-9) != 9 || quotient.quot != 3 || quotient.rem != 2 ||
      first_token == nullptr || second_token == nullptr ||
      std::strcmp(first_token, "kernel") != 0 ||
      std::strcmp(second_token, "libc") != 0 ||
      std::strtok(nullptr, ",") != nullptr || calendar == nullptr ||
      calendar->tm_year != 70 || calendar->tm_mon != 0 ||
      calendar->tm_mday != 1 || std::wcslen(L"wide") != 4 ||
      std::wcschr(L"wide", L'd') == nullptr || !std::iswalpha(L'A')) return 1;

  char buffer[32];
  int parsed = 0;
  if (std::snprintf(buffer, sizeof(buffer), "value=%d", 37) != 8 ||
      std::sscanf(buffer, "value=%d", &parsed) != 1 || parsed != 37 ||
      std::puts("CSTDIO CMATH TEST PASS") < 0) return 1;
  return 0;
}
