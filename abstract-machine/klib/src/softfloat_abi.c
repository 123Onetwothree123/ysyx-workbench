#include <stdint.h>

#include "softfloat/softfloat.h"

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)

typedef union {
  float value;
  uint32_t bits;
} klib_float_bits;

typedef union {
  double value;
  uint64_t bits;
} klib_double_bits;

static float32_t klib_to_f32(float value)
{
  klib_float_bits repr = {.value = value};
  return (float32_t){.v = repr.bits};
}

static float klib_from_f32(float32_t value)
{
  klib_float_bits repr = {.bits = value.v};
  return repr.value;
}

static float64_t klib_to_f64(double value)
{
  klib_double_bits repr = {.value = value};
  return (float64_t){.v = repr.bits};
}

static double klib_from_f64(float64_t value)
{
  klib_double_bits repr = {.bits = value.v};
  return repr.value;
}

static int klib_f32_is_nan(float32_t value)
{
  return (value.v & UINT32_C(0x7fffffff)) > UINT32_C(0x7f800000);
}

static int klib_f64_is_nan(float64_t value)
{
  return (value.v & UINT64_C(0x7fffffffffffffff)) >
         UINT64_C(0x7ff0000000000000);
}

float __addsf3(float a, float b)
{
  return klib_from_f32(f32_add(klib_to_f32(a), klib_to_f32(b)));
}

float __subsf3(float a, float b)
{
  return klib_from_f32(f32_sub(klib_to_f32(a), klib_to_f32(b)));
}

float __mulsf3(float a, float b)
{
  return klib_from_f32(f32_mul(klib_to_f32(a), klib_to_f32(b)));
}

float __divsf3(float a, float b)
{
  return klib_from_f32(f32_div(klib_to_f32(a), klib_to_f32(b)));
}

double __adddf3(double a, double b)
{
  return klib_from_f64(f64_add(klib_to_f64(a), klib_to_f64(b)));
}

double __subdf3(double a, double b)
{
  return klib_from_f64(f64_sub(klib_to_f64(a), klib_to_f64(b)));
}

double __muldf3(double a, double b)
{
  return klib_from_f64(f64_mul(klib_to_f64(a), klib_to_f64(b)));
}

double __divdf3(double a, double b)
{
  return klib_from_f64(f64_div(klib_to_f64(a), klib_to_f64(b)));
}

float __negsf2(float a)
{
  klib_float_bits repr = {.value = a};
  repr.bits ^= UINT32_C(0x80000000);
  return repr.value;
}

double __negdf2(double a)
{
  klib_double_bits repr = {.value = a};
  repr.bits ^= UINT64_C(0x8000000000000000);
  return repr.value;
}

double __extendsfdf2(float a)
{
  return klib_from_f64(f32_to_f64(klib_to_f32(a)));
}

float __truncdfsf2(double a)
{
  return klib_from_f32(f64_to_f32(klib_to_f64(a)));
}

int __fixsfsi(float a)
{
  return (int)f32_to_i32_r_minMag(klib_to_f32(a), false);
}

unsigned int __fixunssfsi(float a)
{
  return (unsigned int)f32_to_ui32_r_minMag(klib_to_f32(a), false);
}

long long __fixsfdi(float a)
{
  return (long long)f32_to_i64_r_minMag(klib_to_f32(a), false);
}

unsigned long long __fixunssfdi(float a)
{
  return (unsigned long long)f32_to_ui64_r_minMag(klib_to_f32(a), false);
}

int __fixdfsi(double a)
{
  return (int)f64_to_i32_r_minMag(klib_to_f64(a), false);
}

unsigned int __fixunsdfsi(double a)
{
  return (unsigned int)f64_to_ui32_r_minMag(klib_to_f64(a), false);
}

long long __fixdfdi(double a)
{
  return (long long)f64_to_i64_r_minMag(klib_to_f64(a), false);
}

unsigned long long __fixunsdfdi(double a)
{
  return (unsigned long long)f64_to_ui64_r_minMag(klib_to_f64(a), false);
}

float __floatsisf(int a)
{
  return klib_from_f32(i32_to_f32(a));
}

float __floatunsisf(unsigned int a)
{
  return klib_from_f32(ui32_to_f32(a));
}

float __floatdisf(long long a)
{
  return klib_from_f32(i64_to_f32(a));
}

float __floatundisf(unsigned long long a)
{
  return klib_from_f32(ui64_to_f32(a));
}

double __floatsidf(int a)
{
  return klib_from_f64(i32_to_f64(a));
}

double __floatunsidf(unsigned int a)
{
  return klib_from_f64(ui32_to_f64(a));
}

double __floatdidf(long long a)
{
  return klib_from_f64(i64_to_f64(a));
}

double __floatundidf(unsigned long long a)
{
  return klib_from_f64(ui64_to_f64(a));
}

/*
 * GCC/libgcc and LLVM/compiler-rt use the same comparison-helper ABI: the
 * predicate is communicated through the sign of the integer result.  The two
 * ordered groups differ only in their NaN result: LE/LT/CMP use +1, while
 * GE/GT use -1.
 */
static int klib_compare_f32_le(float32_t a, float32_t b)
{
  if (f32_lt(a, b)) return -1;
  if (f32_eq(a, b)) return 0;
  return 1;
}

static int klib_compare_f32_ge(float32_t a, float32_t b)
{
  if (f32_lt(a, b)) return -1;
  if (f32_eq(a, b)) return 0;
  return klib_f32_is_nan(a) || klib_f32_is_nan(b) ? -1 : 1;
}

static int klib_compare_f64_le(float64_t a, float64_t b)
{
  if (f64_lt(a, b)) return -1;
  if (f64_eq(a, b)) return 0;
  return 1;
}

static int klib_compare_f64_ge(float64_t a, float64_t b)
{
  if (f64_lt(a, b)) return -1;
  if (f64_eq(a, b)) return 0;
  return klib_f64_is_nan(a) || klib_f64_is_nan(b) ? -1 : 1;
}

int __cmpsf2(float a, float b)
{
  return klib_compare_f32_le(klib_to_f32(a), klib_to_f32(b));
}

int __lesf2(float a, float b)
{
  return klib_compare_f32_le(klib_to_f32(a), klib_to_f32(b));
}

int __ltsf2(float a, float b)
{
  return klib_compare_f32_le(klib_to_f32(a), klib_to_f32(b));
}

int __gesf2(float a, float b)
{
  return klib_compare_f32_ge(klib_to_f32(a), klib_to_f32(b));
}

int __gtsf2(float a, float b)
{
  return klib_compare_f32_ge(klib_to_f32(a), klib_to_f32(b));
}

int __eqsf2(float a, float b)
{
  return f32_eq(klib_to_f32(a), klib_to_f32(b)) ? 0 : 1;
}

int __nesf2(float a, float b)
{
  return f32_eq(klib_to_f32(a), klib_to_f32(b)) ? 0 : 1;
}

int __unordsf2(float a, float b)
{
  const float32_t soft_a = klib_to_f32(a);
  const float32_t soft_b = klib_to_f32(b);
  const int a_is_ordered = f32_eq(soft_a, soft_a);
  const int b_is_ordered = f32_eq(soft_b, soft_b);
  return !(a_is_ordered && b_is_ordered);
}

int __cmpdf2(double a, double b)
{
  return klib_compare_f64_le(klib_to_f64(a), klib_to_f64(b));
}

int __ledf2(double a, double b)
{
  return klib_compare_f64_le(klib_to_f64(a), klib_to_f64(b));
}

int __ltdf2(double a, double b)
{
  return klib_compare_f64_le(klib_to_f64(a), klib_to_f64(b));
}

int __gedf2(double a, double b)
{
  return klib_compare_f64_ge(klib_to_f64(a), klib_to_f64(b));
}

int __gtdf2(double a, double b)
{
  return klib_compare_f64_ge(klib_to_f64(a), klib_to_f64(b));
}

int __eqdf2(double a, double b)
{
  return f64_eq(klib_to_f64(a), klib_to_f64(b)) ? 0 : 1;
}

int __nedf2(double a, double b)
{
  return f64_eq(klib_to_f64(a), klib_to_f64(b)) ? 0 : 1;
}

int __unorddf2(double a, double b)
{
  const float64_t soft_a = klib_to_f64(a);
  const float64_t soft_b = klib_to_f64(b);
  const int a_is_ordered = f64_eq(soft_a, soft_a);
  const int b_is_ordered = f64_eq(soft_b, soft_b);
  return !(a_is_ordered && b_is_ordered);
}

#endif
