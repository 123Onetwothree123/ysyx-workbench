#ifndef KLIB_MATH_H__
#define KLIB_MATH_H__

#if defined(__ISA_NATIVE__) && !defined(__NATIVE_USE_KLIB__)
#include_next <math.h>
#else

#ifdef __cplusplus
extern "C" {
#endif

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif
#ifndef M_E
#define M_E 2.71828182845904523536
#endif

#define FP_NAN 0
#define FP_INFINITE 1
#define FP_ZERO 2
#define FP_SUBNORMAL 3
#define FP_NORMAL 4

#define HUGE_VAL (__builtin_huge_val())
#define HUGE_VALF (__builtin_huge_valf())
#define HUGE_VALL (__builtin_huge_vall())
#define INFINITY (__builtin_inff())
#define NAN (__builtin_nanf(""))

#define MATH_ERRNO 1
#define MATH_ERREXCEPT 2
/* KLIB reports implemented math domain/range errors through errno. */
#define math_errhandling MATH_ERRNO

#if __FLT_EVAL_METHOD__ == 0
typedef float float_t;
typedef double double_t;
#elif __FLT_EVAL_METHOD__ == 1
typedef double float_t;
typedef double double_t;
#elif __FLT_EVAL_METHOD__ == 2
typedef long double float_t;
typedef long double double_t;
#else
#error "KLIB math: unsupported floating-point evaluation method"
#endif

int __klib_fpclassifyf(float x);
int __klib_fpclassify(double x);
int __klib_fpclassifyl(long double x);
int __klib_signbitf(float x);
int __klib_signbit(double x);
int __klib_signbitl(long double x);

double fabs(double x);
float fabsf(float x);
long double fabsl(long double x);
double copysign(double x, double y);
float copysignf(float x, float y);
long double copysignl(long double x, long double y);
double floor(double x);
double ceil(double x);
double round(double x);
double trunc(double x);
double fmod(double x, double y);
double frexp(double x, int *exponent);
float frexpf(float x, int *exponent);
double ldexp(double x, int exponent);
float ldexpf(float x, int exponent);
double scalbn(double x, int exponent);
float scalbnf(float x, int exponent);
double modf(double x, double *integer_part);
float modff(float x, float *integer_part);
double nextafter(double x, double y);
float nextafterf(float x, float y);
double sqrt(double x);
double cbrt(double x);
double pow(double x, double y);
double exp(double x);
double exp2(double x);
double log(double x);
double log2(double x);
double log10(double x);
double sin(double x);
double cos(double x);
double tan(double x);
double asin(double x);
double acos(double x);
double atan(double x);
double atan2(double y, double x);

#ifdef __cplusplus
}
#endif


/*
 * Classification is routed by type instead of value conversion.  The C11
 * generic-selection controlling expression is not evaluated; the selected
 * function receives the argument exactly once.  C++ overload resolution gives
 * the same single-evaluation property without relying on _Generic.
 */
#ifdef __cplusplus
static inline int __klib_fpclassify_dispatch(float x)
{
  return __klib_fpclassifyf(x);
}
static inline int __klib_fpclassify_dispatch(double x)
{
  return __klib_fpclassify(x);
}
static inline int __klib_fpclassify_dispatch(long double x)
{
  return __klib_fpclassifyl(x);
}
static inline int __klib_signbit_dispatch(float x)
{
  return __klib_signbitf(x);
}
static inline int __klib_signbit_dispatch(double x)
{
  return __klib_signbit(x);
}
static inline int __klib_signbit_dispatch(long double x)
{
  return __klib_signbitl(x);
}
#define __KLIB_FPCLASSIFY(x) (__klib_fpclassify_dispatch((x)))
#define __KLIB_SIGNBIT(x) (__klib_signbit_dispatch((x)))
#else
#define __KLIB_FPCLASSIFY(x)                                                \
  (_Generic((x), float: __klib_fpclassifyf, double: __klib_fpclassify,     \
            long double: __klib_fpclassifyl)((x)))
#define __KLIB_SIGNBIT(x)                                                   \
  (_Generic((x), float: __klib_signbitf, double: __klib_signbit,           \
            long double: __klib_signbitl)((x)))
#endif

#define fpclassify(x) __KLIB_FPCLASSIFY(x)
#define isfinite(x) (__KLIB_FPCLASSIFY(x) >= FP_ZERO)
#define isinf(x) (__KLIB_FPCLASSIFY(x) == FP_INFINITE)
#define isnan(x) (__KLIB_FPCLASSIFY(x) == FP_NAN)
#define isnormal(x) (__KLIB_FPCLASSIFY(x) == FP_NORMAL)
#define signbit(x) __KLIB_SIGNBIT(x)

#endif
#endif
