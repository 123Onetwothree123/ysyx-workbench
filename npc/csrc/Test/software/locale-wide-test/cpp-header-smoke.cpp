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
#include <type_traits>

#ifndef errno
#error "<cerrno> must define errno as a macro"
#endif

static_assert(sizeof(wchar_t) == 4);
static_assert(sizeof(std::mbstate_t) >= sizeof(int) + sizeof(std::wint_t));
static_assert(sizeof(std::time_t) == 8);
static_assert(FP_NAN == 0 && FP_INFINITE == 1 && FP_ZERO == 2 &&
              FP_SUBNORMAL == 3 && FP_NORMAL == 4);
static_assert(std::is_same_v<decltype(std::abs(-1)), int>);
static_assert(std::is_same_v<decltype(std::fabs(1)), double>);
static_assert(std::is_same_v<decltype(std::floor(1.0f)), float>);
static_assert(std::is_same_v<decltype(std::sqrt(1.0f)), float>);
static_assert(std::is_same_v<decltype(std::fmod(3.0f, 2.0f)), float>);
static_assert(std::is_same_v<decltype(std::copysign(1, -1)), double>);
static_assert(std::is_same_v<decltype(assert(true)), void>);

extern "C" int locale_wide_cpp_header_smoke(void)
{
  std::FILE *stream = nullptr;
  double (*sin_function)(double) = &std::sin;
  char text[] = "KLIB";
  const char *constant_text = text;
  char buffer[8];
  (void)stream;
  (void)sin_function;
  assert(sizeof(std::time_t) == 8);
  return std::fpclassify(1.0) == FP_NORMAL && std::isfinite(1.0) &&
         !std::isnan(1.0) && std::signbit(-0.0) &&
         std::copysign(1.0, -0.0) < 0.0 && std::fabs(-1.0f) == 1.0f &&
         std::isalpha('K') && std::tolower('K') == 'k' &&
         std::localeconv() != nullptr && std::strlen(text) == 4 &&
         std::strchr(constant_text, 'I') == constant_text + 2 &&
         std::abs(-7) == 7 && std::wcslen(L"wide") == 4 &&
         std::iswalpha(L'A') &&
         std::snprintf(buffer, sizeof(buffer), "%s", "ok") == 2;
}
