#ifndef KLIB_WCTYPE_H__
#define KLIB_WCTYPE_H__

#if defined(__ISA_NATIVE__) && !defined(__NATIVE_USE_KLIB__)
#include_next <wctype.h>
#else

#include <wchar.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Match the target toolchain ABI so KLIB objects can interoperate with C++
 * headers and other freestanding objects built against newlib headers. */
typedef int wctype_t;
typedef int wctrans_t;

int iswalnum(wint_t wc);
int iswalpha(wint_t wc);
int iswblank(wint_t wc);
int iswcntrl(wint_t wc);
int iswdigit(wint_t wc);
int iswgraph(wint_t wc);
int iswlower(wint_t wc);
int iswprint(wint_t wc);
int iswpunct(wint_t wc);
int iswspace(wint_t wc);
int iswupper(wint_t wc);
int iswxdigit(wint_t wc);
wint_t towlower(wint_t wc);
wint_t towupper(wint_t wc);

wctype_t wctype(const char *property);
int iswctype(wint_t wc, wctype_t property);
wctrans_t wctrans(const char *property);
wint_t towctrans(wint_t wc, wctrans_t conversion);

#ifdef __cplusplus
}
#endif

#endif
#endif
