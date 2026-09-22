#include <locale.h>

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)

#include <klib.h>
#include <limits.h>

static char c_locale_name[] = "C";
static char c_decimal_point[] = ".";
static char c_empty[] = "";

static struct lconv c_locale = {
    .decimal_point = c_decimal_point,
    .thousands_sep = c_empty,
    .grouping = c_empty,
    .int_curr_symbol = c_empty,
    .currency_symbol = c_empty,
    .mon_decimal_point = c_empty,
    .mon_thousands_sep = c_empty,
    .mon_grouping = c_empty,
    .positive_sign = c_empty,
    .negative_sign = c_empty,
    .int_frac_digits = CHAR_MAX,
    .frac_digits = CHAR_MAX,
    .p_cs_precedes = CHAR_MAX,
    .p_sep_by_space = CHAR_MAX,
    .n_cs_precedes = CHAR_MAX,
    .n_sep_by_space = CHAR_MAX,
    .p_sign_posn = CHAR_MAX,
    .n_sign_posn = CHAR_MAX,
    .int_p_cs_precedes = CHAR_MAX,
    .int_p_sep_by_space = CHAR_MAX,
    .int_n_cs_precedes = CHAR_MAX,
    .int_n_sep_by_space = CHAR_MAX,
    .int_p_sign_posn = CHAR_MAX,
    .int_n_sign_posn = CHAR_MAX,
};

static int locale_category_valid(int category)
{
  return category >= LC_ALL && category <= LC_MESSAGES;
}

char *setlocale(int category, const char *locale)
{
  if (!locale_category_valid(category))
  {
    return NULL;
  }
  if (locale == NULL)
  {
    return c_locale_name;
  }
  if (locale[0] == '\0' || strcmp(locale, "C") == 0 ||
      strcmp(locale, "POSIX") == 0)
  {
    return c_locale_name;
  }
  return NULL;
}

struct lconv *localeconv(void)
{
  return &c_locale;
}

int strcoll(const char *left, const char *right)
{
  return strcmp(left, right);
}

size_t strxfrm(char *dst, const char *src, size_t n)
{
  const size_t length = strlen(src);
  if (n != 0)
  {
    const size_t copied = length < n ? length + 1 : n;
    memcpy(dst, src, copied);
  }
  return length;
}

#endif
