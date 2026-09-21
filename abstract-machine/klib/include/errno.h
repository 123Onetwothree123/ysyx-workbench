#ifndef KLIB_ERRNO_H__
#define KLIB_ERRNO_H__

#if defined(__ISA_NATIVE__)
#include_next <errno.h>
#else

#ifdef __cplusplus
extern "C" {
#endif

extern int errno;

#define E2BIG 7
#define ENOMEM 12
#define EFAULT 14
#define EINVAL 22
#define EDOM 33
#define ERANGE 34
#define ENOSYS 38
#define EOVERFLOW 75
#define EILSEQ 84

#ifdef __cplusplus
}
#endif

#endif
#endif
