#ifndef KLIB_STDLIB_H__
#define KLIB_STDLIB_H__

#if defined(__ISA_NATIVE__) && !defined(__NATIVE_USE_KLIB__)
#if defined(__cplusplus) && !defined(_GLIBCXX_INCLUDE_NEXT_C_HEADERS)
/* Skip libstdc++'s freestanding stdlib.h shim and reach the host C header. */
#define _GLIBCXX_INCLUDE_NEXT_C_HEADERS 1
#define KLIB_UNDEF_GLIBCXX_INCLUDE_NEXT_C_HEADERS 1
#endif
#include_next <stdlib.h>
#ifdef KLIB_UNDEF_GLIBCXX_INCLUDE_NEXT_C_HEADERS
#undef KLIB_UNDEF_GLIBCXX_INCLUDE_NEXT_C_HEADERS
#undef _GLIBCXX_INCLUDE_NEXT_C_HEADERS
#endif
#else

#include <klib.h>

#ifndef EXIT_SUCCESS
#define EXIT_SUCCESS 0
#define EXIT_FAILURE 1
#endif
#ifndef RAND_MAX
#define RAND_MAX 32767
#endif
#ifndef MB_CUR_MAX
#define MB_CUR_MAX 1
#endif

#ifdef __cplusplus
extern "C" {
#endif

int mblen(const char *s, size_t n);
int mbtowc(wchar_t *pwc, const char *s, size_t n);
int wctomb(char *s, wchar_t wc);
size_t mbstowcs(wchar_t *dst, const char *src, size_t len);
size_t wcstombs(char *dst, const wchar_t *src, size_t len);

/* Declaration-only compatibility surface for C++ standard-library headers.
 * KLIB does not implement floating parsing, process environments, quick-exit
 * handlers, command processors or aligned allocation yet. */
double atof(const char *string);
float strtof(const char *__restrict string, char **__restrict endptr);
double strtod(const char *__restrict string, char **__restrict endptr);
long double strtold(const char *__restrict string, char **__restrict endptr);
char *getenv(const char *name);
int system(const char *command);
void *aligned_alloc(size_t alignment, size_t size);
int at_quick_exit(void (*function)(void));
void quick_exit(int status) __attribute__((noreturn));

#ifdef __cplusplus
}
#endif

#endif
#endif
