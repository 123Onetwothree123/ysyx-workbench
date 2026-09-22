#include <stdlib.h>
#include <stdio.h>
#include <wchar.h>
#include <locale.h>
#include <wctype.h>
#include <time.h>

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
static_assert(sizeof(mbstate_t) == 8, "target mbstate_t ABI changed");
static_assert(sizeof(wctype_t) == 4, "target wctype_t ABI changed");
static_assert(sizeof(wctrans_t) == 4, "target wctrans_t ABI changed");
static_assert(sizeof(time_t) == 8, "target RV32 time_t ABI changed");
static_assert(EXIT_SUCCESS == 0 && EXIT_FAILURE != 0,
              "stdlib exit macros missing");
static_assert(RAND_MAX == 32767, "rand ABI changed");
static_assert(FOPEN_MAX >= 8, "stdio must support at least eight streams");
static_assert(LC_ALL == 0 && LC_COLLATE == 1 && LC_CTYPE == 2 &&
                  LC_MONETARY == 3 && LC_NUMERIC == 4 && LC_TIME == 5 &&
                  LC_MESSAGES == 6,
              "target locale category ABI changed");
static_assert(offsetof(struct lconv, int_n_sign_posn) <
                  offsetof(struct lconv, int_p_cs_precedes),
              "target lconv ABI changed");
#endif

int locale_wide_include_order_probe(void)
{
  mbstate_t state = {0};
  const int locale_width_valid =
#if defined(__ISA_NATIVE__) && !defined(__NATIVE_USE_KLIB__)
      MB_CUR_MAX >= 1;
#else
      MB_CUR_MAX == 1;
#endif
  return locale_width_valid && mbsinit(&state) && LC_ALL >= 0 &&
         wctype("alpha") != 0;
}
