#if defined(__ISA_NATIVE__) && !defined(__NATIVE_USE_KLIB__)
#include_next <assert.h>
#else

#ifndef KLIB_ASSERT_H__
#define KLIB_ASSERT_H__
#include <klib.h>
#endif

#undef assert
#ifdef NDEBUG
#define assert(ignore) ((void)0)
#else
#define assert(condition)                                                   \
  ((condition)                                                              \
       ? (void)0                                                            \
       : (printf("Assertion failed: %s, function %s, file %s, line %d.\n", \
                 #condition, __builtin_FUNCTION(), __FILE__, __LINE__),     \
          abort()))
#endif

#endif
