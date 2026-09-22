#ifndef KLIB_LOCALE_H__
#define KLIB_LOCALE_H__

#if defined(__ISA_NATIVE__) && !defined(__NATIVE_USE_KLIB__)
#include_next <locale.h>
#else

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Keep the public ABI aligned with the target newlib headers used by the
 * RISC-V toolchain, even though KLIB currently supplies only C/POSIX. */
#define LC_ALL 0
#define LC_COLLATE 1
#define LC_CTYPE 2
#define LC_MONETARY 3
#define LC_NUMERIC 4
#define LC_TIME 5
#define LC_MESSAGES 6

struct lconv {
  char *decimal_point;
  char *thousands_sep;
  char *grouping;
  char *int_curr_symbol;
  char *currency_symbol;
  char *mon_decimal_point;
  char *mon_thousands_sep;
  char *mon_grouping;
  char *positive_sign;
  char *negative_sign;
  char int_frac_digits;
  char frac_digits;
  char p_cs_precedes;
  char p_sep_by_space;
  char n_cs_precedes;
  char n_sep_by_space;
  char p_sign_posn;
  char n_sign_posn;
  char int_n_cs_precedes;
  char int_n_sep_by_space;
  char int_n_sign_posn;
  char int_p_cs_precedes;
  char int_p_sep_by_space;
  char int_p_sign_posn;
};

char *setlocale(int category, const char *locale);
struct lconv *localeconv(void);

/* These declarations normally live in string.h. */
int strcoll(const char *left, const char *right);
size_t strxfrm(char *dst, const char *src, size_t n);

#ifdef __cplusplus
}
#endif

#endif
#endif
