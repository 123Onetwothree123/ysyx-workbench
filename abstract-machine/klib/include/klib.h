#ifndef KLIB_H__
#define KLIB_H__

#include <am.h>
#include <stddef.h>
#include <stdarg.h>
/*
https://git.musl-libc.org/cgit/musl/commit/include/stdio.h?id=400c5e5c8307a2ebe44ef1f203f5a15669f20347
改的，他妈的，来骗来偷袭我一个19岁的老东西
*/
#ifndef __restrict
#if defined(__GNUC__) || defined(__clang__) || defined(_MSC_VER)
#define __restrict __restrict__
#elif defined(__STDC_VERSION__) && __STDC_VERSION__ >= 199901L
#define __restrict restrict
#else
#define __restrict
#endif
#endif

#ifdef __cplusplus
extern "C" {
#endif

//#define __NATIVE_USE_KLIB__

//自己写的
typedef struct FILE KFILE;//你妈的傻逼io.h，被迫改名FILE为KFILE
extern KFILE *kstdout;

// string.h
void  *memset    (void *s, int c, size_t n);
void  *memcpy    (void *dst, const void *src, size_t n);
void  *memmove   (void *dst, const void *src, size_t n);
int    memcmp    (const void *s1, const void *s2, size_t n);
size_t strlen    (const char *s);
char  *strcat    (char *dst, const char *src);
char  *strcpy    (char *dst, const char *src);
char  *strncpy   (char *dst, const char *src, size_t n);
int    strcmp    (const char *s1, const char *s2);
int    strncmp   (const char *s1, const char *s2, size_t n);

// stdlib.h
void   srand     (unsigned int seed);
int    rand      (void);
void  *malloc    (size_t size);
void   free      (void *ptr);
int    abs       (int x);
int    atoi      (const char *nptr);

// stdio.h
int    printf    (const char *format, ...);
int    sprintf   (char *str, const char *format, ...);
int    snprintf  (char *str, size_t size, const char *format, ...);
int    vsprintf  (char *str, const char *format, va_list ap);
int    vsnprintf (char *str, size_t size, const char *format, va_list ap);
//自己写的
int kvfprintf(KFILE *__restrict stream, const char *__restrict format, va_list ap);
int kfprintf(KFILE *__restrict stream, const char *__restrict format, ...);

// assert.h
#ifdef NDEBUG
  #define assert(ignore) ((void)0)
#else
  #define assert(cond) \
    do { \
      if (!(cond)) { \
        printf("Assertion fail at %s:%d\n", __FILE__, __LINE__); \
        halt(1); \
      } \
    } while (0)
#endif

#ifdef __cplusplus
}
#endif

#endif
