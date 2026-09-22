#ifndef KLIB_WCHAR_H__
#define KLIB_WCHAR_H__

#if defined(__ISA_NATIVE__) && !defined(__NATIVE_USE_KLIB__)
#include_next <wchar.h>
#else

#include <stddef.h>
#include <stdint.h>
#include <stdarg.h>

#ifdef __cplusplus
extern "C" {
#endif

struct tm;

#ifndef __wint_t_defined
#define __wint_t_defined 1
typedef __WINT_TYPE__ wint_t;
#endif

#ifndef __mbstate_t_defined
#define __mbstate_t_defined 1
typedef struct {
  int __count;
  union {
    wint_t __wch;
    unsigned char __wchb[4];
  } __value;
} mbstate_t;
#endif

#ifndef WCHAR_MIN
#define WCHAR_MIN __WCHAR_MIN__
#endif
#ifndef WCHAR_MAX
#define WCHAR_MAX __WCHAR_MAX__
#endif
#ifndef WINT_MIN
#define WINT_MIN ((wint_t)0)
#endif
#ifndef WINT_MAX
#define WINT_MAX ((wint_t)-1)
#endif
#ifndef WEOF
#define WEOF ((wint_t)-1)
#endif

size_t wcslen(const wchar_t *s);
size_t wcsnlen(const wchar_t *s, size_t maxlen);
wchar_t *wcscpy(wchar_t *dst, const wchar_t *src);
wchar_t *wcsncpy(wchar_t *dst, const wchar_t *src, size_t n);
wchar_t *wcscat(wchar_t *dst, const wchar_t *src);
wchar_t *wcsncat(wchar_t *dst, const wchar_t *src, size_t n);
int wcscmp(const wchar_t *left, const wchar_t *right);
int wcsncmp(const wchar_t *left, const wchar_t *right, size_t n);
wchar_t *wcschr(const wchar_t *s, wchar_t c);
wchar_t *wcsrchr(const wchar_t *s, wchar_t c);
wchar_t *wcsstr(const wchar_t *haystack, const wchar_t *needle);
size_t wcsspn(const wchar_t *s, const wchar_t *accept);
size_t wcscspn(const wchar_t *s, const wchar_t *reject);
wchar_t *wcspbrk(const wchar_t *s, const wchar_t *accept);
wchar_t *wcstok(wchar_t *s, const wchar_t *delim, wchar_t **saveptr);

wchar_t *wmemchr(const wchar_t *s, wchar_t c, size_t n);
int wmemcmp(const wchar_t *left, const wchar_t *right, size_t n);
wchar_t *wmemcpy(wchar_t *dst, const wchar_t *src, size_t n);
wchar_t *wmemmove(wchar_t *dst, const wchar_t *src, size_t n);
wchar_t *wmemset(wchar_t *dst, wchar_t c, size_t n);

int wcscoll(const wchar_t *left, const wchar_t *right);
size_t wcsxfrm(wchar_t *dst, const wchar_t *src, size_t n);
size_t wcsftime(wchar_t *s, size_t maxsize, const wchar_t *format,
                const struct tm *timeptr);
long wcstol(const wchar_t *s, wchar_t **endptr, int base);
unsigned long wcstoul(const wchar_t *s, wchar_t **endptr, int base);
long long wcstoll(const wchar_t *s, wchar_t **endptr, int base);
unsigned long long wcstoull(const wchar_t *s, wchar_t **endptr, int base);

wint_t btowc(int c);
int wctob(wint_t c);
int mbsinit(const mbstate_t *state);
size_t mbrlen(const char *s, size_t n, mbstate_t *state);
size_t mbrtowc(wchar_t *pwc, const char *s, size_t n, mbstate_t *state);
size_t wcrtomb(char *s, wchar_t wc, mbstate_t *state);
size_t mbsrtowcs(wchar_t *dst, const char **src, size_t len,
                 mbstate_t *state);
size_t wcsrtombs(char *dst, const wchar_t **src, size_t len,
                 mbstate_t *state);

int mblen(const char *s, size_t n);
int mbtowc(wchar_t *pwc, const char *s, size_t n);
int wctomb(char *s, wchar_t wc);
size_t mbstowcs(wchar_t *dst, const char *src, size_t len);
size_t wcstombs(char *dst, const wchar_t *src, size_t len);

/*
 * Declaration-only compatibility surface for hosted C++ headers.  The
 * freestanding KLIB does not implement wide-oriented FILE I/O, wide printf
 * or floating-point wide-number parsing yet; code that calls these symbols
 * is intentionally rejected by the linker rather than silently stubbed.
 */
typedef struct FILE FILE;
wint_t fgetwc(FILE *stream);
wchar_t *fgetws(wchar_t *s, int n, FILE *stream);
wint_t fputwc(wchar_t wc, FILE *stream);
int fputws(const wchar_t *s, FILE *stream);
int fwide(FILE *stream, int mode);
int fwprintf(FILE *stream, const wchar_t *format, ...);
int fwscanf(FILE *stream, const wchar_t *format, ...);
wint_t getwc(FILE *stream);
wint_t getwchar(void);
wint_t putwc(wchar_t wc, FILE *stream);
wint_t putwchar(wchar_t wc);
int swprintf(wchar_t *s, size_t n, const wchar_t *format, ...);
int swscanf(const wchar_t *s, const wchar_t *format, ...);
wint_t ungetwc(wint_t wc, FILE *stream);
int vfwprintf(FILE *stream, const wchar_t *format, va_list ap);
int vfwscanf(FILE *stream, const wchar_t *format, va_list ap);
int vswprintf(wchar_t *s, size_t n, const wchar_t *format, va_list ap);
int vswscanf(const wchar_t *s, const wchar_t *format, va_list ap);
int vwprintf(const wchar_t *format, va_list ap);
int vwscanf(const wchar_t *format, va_list ap);
int wprintf(const wchar_t *format, ...);
int wscanf(const wchar_t *format, ...);
double wcstod(const wchar_t *s, wchar_t **endptr);
float wcstof(const wchar_t *s, wchar_t **endptr);
long double wcstold(const wchar_t *s, wchar_t **endptr);

#ifdef __cplusplus
}
#endif

#endif
#endif
