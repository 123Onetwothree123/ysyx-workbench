#ifndef KLIB_H__
#define KLIB_H__

#include <am.h>
#include <stddef.h>
#include <stdint.h>
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

/*
 * The freestanding build owns its FILE type.  A normal native build keeps
 * using the host libc's FILE and exposes only the legacy opaque KFILE name.
 */
#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
typedef struct FILE FILE;
typedef FILE KFILE;
typedef long fpos_t;
typedef struct {
  /* VFS adapter contract: failures return a negative value and set errno;
   * open interprets the ISO mode string (create/truncate/exclusive flags).
   * Keep one table installed until every FILE created from it is closed. */
  int (*open)(const char *path, const char *mode);
  ptrdiff_t (*read)(int fd, void *buf, size_t len);
  ptrdiff_t (*write)(int fd, const void *buf, size_t len);
  int64_t (*seek)(int fd, int64_t offset, int whence);
  int (*close)(int fd);
  int (*remove)(const char *path);
  int (*rename)(const char *old_path, const char *new_path);
} KFILE_FD_OPS;
extern FILE *stdin;
extern FILE *stdout;
extern FILE *stderr;
extern KFILE *kstdin;
extern KFILE *kstdout;
extern KFILE *kstderr;
#else
typedef struct KFILE KFILE;
extern KFILE *kstdin;
extern KFILE *kstdout;
extern KFILE *kstderr;
#endif

#if (!defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)) && \
    !defined(_STDLIB_H_) && \
    !defined(_STDLIB_H)
typedef struct {
  int quot;
  int rem;
} div_t;
typedef struct {
  long quot;
  long rem;
} ldiv_t;
typedef struct {
  long long quot;
  long long rem;
} lldiv_t;
#endif

#ifndef EOF
#define EOF (-1)
#endif
#ifndef BUFSIZ
#define BUFSIZ 256
#endif
#ifndef FILENAME_MAX
#define FILENAME_MAX 4096
#endif
#ifndef FOPEN_MAX
#define FOPEN_MAX 16
#endif
#ifndef L_tmpnam
#define L_tmpnam 20
#endif
#ifndef TMP_MAX
#define TMP_MAX 10000
#endif
#ifndef _IOFBF
#define _IOFBF 0
#define _IOLBF 1
#define _IONBF 2
#endif
#ifndef SEEK_SET
#define SEEK_SET 0
#define SEEK_CUR 1
#define SEEK_END 2
#endif

// string.h
void  *memset    (void *s, int c, size_t n);
void  *memcpy    (void *dst, const void *src, size_t n);
void  *memmove   (void *dst, const void *src, size_t n);
void  *mempcpy   (void *__restrict dst, const void *__restrict src, size_t n);
int    memcmp    (const void *s1, const void *s2, size_t n);
void  *(memchr)  (const void *s, int c, size_t n);
void  *(memmem)  (const void *haystack, size_t haystack_len,
                  const void *needle, size_t needle_len);
size_t strlen    (const char *s);
size_t strnlen   (const char *s, size_t maxlen);
size_t strspn    (const char *s, const char *accept);
size_t strcspn   (const char *s, const char *reject);
char  *(strpbrk) (const char *s, const char *accept);
char  *(strchr)  (const char *s, int c);
char  *(strrchr) (const char *s, int c);
char  *(strstr)  (const char *haystack, const char *needle);
char  *strtok_r  (char *__restrict s, const char *__restrict delim,
                  char **__restrict saveptr);
char  *strtok    (char *__restrict s, const char *__restrict delim);
char  *strsep    (char **__restrict stringp, const char *__restrict delim);
int    strcasecmp (const char *s1, const char *s2);
int    strncasecmp(const char *s1, const char *s2, size_t n);
char  *strdup    (const char *s);
char  *strndup   (const char *s, size_t n);
char  *strerror  (int errnum);
#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
int    strerror_r(int errnum, char *buf, size_t buflen);
#endif
ptrdiff_t (strscpy) (char *dst, const char *src, size_t dstsize);
size_t (strlcpy)    (char *dst, const char *src, size_t dstsize);
size_t (strlcat)    (char *dst, const char *src, size_t dstsize);
char  *strcat    (char *dst, const char *src);
char  *strncat   (char *__restrict dst, const char *__restrict src, size_t n);
char  *strcpy    (char *dst, const char *src);
char  *stpcpy    (char *__restrict dst, const char *__restrict src);
char  *strncpy   (char *dst, const char *src, size_t n);
char  *stpncpy   (char *__restrict dst, const char *__restrict src, size_t n);
int    strcmp    (const char *s1, const char *s2);
int    strncmp   (const char *s1, const char *s2, size_t n);
int    strcoll   (const char *s1, const char *s2);
size_t strxfrm   (char *dst, const char *src, size_t n);
#if defined(__ISA_NATIVE__) && defined(__NATIVE_USE_KLIB__)
/* Native AM itself uses this host extension for signal diagnostics. */
char  *strsignal (int signal_number);
#endif

// stdlib.h
void   srand     (unsigned int seed);
int    rand      (void);
void  *malloc    (size_t size);
void  *calloc    (size_t count, size_t size);
void  *realloc   (void *ptr, size_t new_size);
void   free      (void *ptr);
int    atexit    (void (*function)(void));
void   exit      (int status) __attribute__((noreturn));
void   _Exit     (int status) __attribute__((noreturn));
void   abort     (void) __attribute__((noreturn));
/* Run compiler-emitted static initialization before entering main(). */
void   __klib_init_array(void);
int    abs       (int x);
long   labs      (long x);
long long llabs  (long long x);
int    atoi      (const char *nptr);
long   atol      (const char *nptr);
long long atoll  (const char *nptr);
#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
div_t  div       (int numer, int denom);
ldiv_t ldiv      (long numer, long denom);
lldiv_t lldiv    (long long numer, long long denom);
#endif
long   strtol    (const char *__restrict nptr, char **__restrict endptr, int base);
unsigned long strtoul(const char *__restrict nptr, char **__restrict endptr, int base);
long long strtoll(const char *__restrict nptr, char **__restrict endptr, int base);
unsigned long long strtoull(const char *__restrict nptr, char **__restrict endptr, int base);
intmax_t strtoimax(const char *__restrict nptr, char **__restrict endptr, int base);
uintmax_t strtoumax(const char *__restrict nptr, char **__restrict endptr, int base);
void  *(bsearch)  (const void *key, const void *base, size_t nmemb, size_t size,
                   int (*compar)(const void *, const void *));
void   qsort     (void *base, size_t nmemb, size_t size,
                  int (*compar)(const void *, const void *));
#if defined(__ISA_NATIVE__) && defined(__NATIVE_USE_KLIB__)
/* Native AM platform glue obtains its launch settings from the host. */
char  *getenv    (const char *name);
#endif

// stdio.h
int    putchar   (int c);
int    puts      (const char *s);
void   perror    (const char *s);
int    vprintf   (const char *format, va_list ap);
int    printf    (const char *format, ...);
int    sprintf   (char *str, const char *format, ...);
int    snprintf  (char *str, size_t size, const char *format, ...);
int    vsprintf  (char *str, const char *format, va_list ap);
int    vsnprintf (char *str, size_t size, const char *format, va_list ap);
int    sscanf    (const char *__restrict str,
                  const char *__restrict format, ...);
int    vsscanf   (const char *__restrict str,
                  const char *__restrict format, va_list ap);
int    scanf     (const char *__restrict format, ...);
int    vscanf    (const char *__restrict format, va_list ap);
#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
int    fscanf    (FILE *__restrict stream,
                  const char *__restrict format, ...);
int    vfscanf   (FILE *__restrict stream,
                  const char *__restrict format, va_list ap);
int    fputc     (int c, FILE *stream);
int    putc      (int c, FILE *stream);
int    fputs     (const char *__restrict s, FILE *__restrict stream);
size_t fwrite    (const void *__restrict ptr, size_t size, size_t nmemb,
                  FILE *__restrict stream);
int    fflush    (FILE *stream);
int    ferror    (FILE *stream);
int    feof      (FILE *stream);
void   clearerr  (FILE *stream);
int    fgetc     (FILE *stream);
int    getc      (FILE *stream);
int    getchar   (void);
char  *fgets     (char *__restrict s, int n, FILE *__restrict stream);
size_t fread     (void *__restrict ptr, size_t size, size_t nmemb,
                  FILE *__restrict stream);
int    ungetc    (int c, FILE *stream);
int    setvbuf   (FILE *__restrict stream, char *__restrict buf,
                  int mode, size_t size);
void   setbuf    (FILE *__restrict stream, char *__restrict buf);
int    fileno    (FILE *stream);
int    fclose    (FILE *stream);
int    fseek     (FILE *stream, long offset, int whence);
long   ftell     (FILE *stream);
void   rewind    (FILE *stream);
int    fgetpos   (FILE *__restrict stream, fpos_t *__restrict position);
int    fsetpos   (FILE *stream, const fpos_t *position);
FILE  *fdopen    (int fd, const char *mode);
FILE  *fopen     (const char *__restrict path, const char *__restrict mode);
FILE  *freopen   (const char *__restrict path, const char *__restrict mode,
                  FILE *__restrict stream);
int    kfile_set_fd_ops(const KFILE_FD_OPS *ops);
int    remove    (const char *path);
int    rename    (const char *old_path, const char *new_path);
FILE  *tmpfile   (void);
char  *tmpnam    (char *s);
int    vfprintf (FILE *__restrict stream, const char *__restrict format,
                 va_list ap);
int    fprintf  (FILE *__restrict stream, const char *__restrict format, ...);
#endif
//自己写的
int kvfprintf(KFILE *__restrict stream, const char *__restrict format, va_list ap);
int kfprintf(KFILE *__restrict stream, const char *__restrict format, ...);

// assert.h
#ifndef assert
  #ifdef NDEBUG
    #define assert(ignore) ((void)0)
  #else
    #define assert(condition)                                               \
      ((condition)                                                          \
           ? (void)0                                                        \
           : (printf(                                                       \
                  "Assertion failed: %s, function %s, file %s, line %d.\n", \
                  #condition, __builtin_FUNCTION(), __FILE__, __LINE__),    \
              abort()))
  #endif
#endif

#ifdef __cplusplus
}
#endif

#endif
