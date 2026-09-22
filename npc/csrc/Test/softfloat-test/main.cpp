#include <am.h>
#include <errno.h>
#include <klib.h>
#include <limits.h>
#include <math.h>
#include <stdint.h>

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
extern "C" {
extern float __negsf2(float);
extern double __negdf2(double);
extern unsigned long long __fixunssfdi(float);
extern unsigned long long __fixunsdfdi(double);
extern int __cmpsf2(float, float);
extern int __lesf2(float, float);
extern int __gesf2(float, float);
extern int __eqsf2(float, float);
extern int __nesf2(float, float);
extern int __unordsf2(float, float);
extern int __cmpdf2(double, double);
extern int __ledf2(double, double);
extern int __gedf2(double, double);
extern int __eqdf2(double, double);
extern int __nedf2(double, double);
extern int __unorddf2(double, double);
}
#endif

static int failures;
static int classification_evaluations;

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
extern "C" uint_fast8_t softfloat_exceptionFlags;
#define TEST_SOFTFLOAT_INEXACT 1
#define TEST_SOFTFLOAT_UNDERFLOW 2
#define TEST_SOFTFLOAT_OVERFLOW 4
#define TEST_SOFTFLOAT_INVALID 16
#endif

#define CHECK(condition)                                                     \
  do {                                                                       \
    if (!(condition)) {                                                      \
      failures++;                                                            \
      printf("softfloat check failed at line %d\n", __LINE__);             \
    }                                                                        \
  } while (0)

static uint32_t float_bits(float value)
{
  uint32_t bits;
  memcpy(&bits, &value, sizeof(bits));
  return bits;
}

static uint64_t double_bits(double value)
{
  uint64_t bits;
  memcpy(&bits, &value, sizeof(bits));
  return bits;
}

static float float_from_bits(uint32_t bits)
{
  float value;
  memcpy(&value, &bits, sizeof(value));
  return value;
}

static double double_from_bits(uint64_t bits)
{
  double value;
  memcpy(&value, &bits, sizeof(value));
  return value;
}

static float counted_float(float value)
{
  classification_evaluations++;
  return value;
}

static double counted_double(double value)
{
  classification_evaluations++;
  return value;
}

static long double counted_long_double(long double value)
{
  classification_evaluations++;
  return value;
}

static int long_double_same_magnitude(long double left, long double right)
{
  unsigned char left_bytes[sizeof(long double)];
  unsigned char right_bytes[sizeof(long double)];
  memcpy(left_bytes, &left, sizeof(left_bytes));
  memcpy(right_bytes, &right, sizeof(right_bytes));

#if __LDBL_MANT_DIG__ == 113 && __SIZEOF_LONG_DOUBLE__ == 16
#if __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
  const size_t sign_byte = 15;
#else
  const size_t sign_byte = 0;
#endif
  left_bytes[sign_byte] &= 0x7f;
  right_bytes[sign_byte] &= 0x7f;
  return memcmp(left_bytes, right_bytes, sizeof(left_bytes)) == 0;
#elif __LDBL_MANT_DIG__ == 64 && \
      (__SIZEOF_LONG_DOUBLE__ == 12 || __SIZEOF_LONG_DOUBLE__ == 16) && \
      __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
  left_bytes[9] &= 0x7f;
  right_bytes[9] &= 0x7f;
  return memcmp(left_bytes, right_bytes, 10) == 0;
#elif __LDBL_MANT_DIG__ == 53 && __SIZEOF_LONG_DOUBLE__ == 8
#if __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
  const size_t sign_byte = 7;
#else
  const size_t sign_byte = 0;
#endif
  left_bytes[sign_byte] &= 0x7f;
  right_bytes[sign_byte] &= 0x7f;
  return memcmp(left_bytes, right_bytes, sizeof(left_bytes)) == 0;
#else
#error "softfloat-test: unsupported long double representation"
#endif
}

static int float_bits_are_nan(uint32_t bits)
{
  return (bits & UINT32_C(0x7fffffff)) > UINT32_C(0x7f800000);
}

static int double_bits_are_nan(uint64_t bits)
{
  return (bits & UINT64_C(0x7fffffffffffffff)) >
         UINT64_C(0x7ff0000000000000);
}

static void test_float_arithmetic(void)
{
  volatile float a = 1.5f;
  volatile float b = 2.25f;
  volatile float zero = 0.0f;
  volatile float neg_zero = float_from_bits(UINT32_C(0x80000000));
  volatile float inf = float_from_bits(UINT32_C(0x7f800000));
  volatile float neg_inf = float_from_bits(UINT32_C(0xff800000));
  volatile float qnan = float_from_bits(UINT32_C(0x7fc12345));
  volatile float min_subnormal = float_from_bits(UINT32_C(1));

  CHECK(float_bits(a + b) == UINT32_C(0x40700000));
  CHECK(float_bits(b - a) == UINT32_C(0x3f400000));
  CHECK(float_bits(a * b) == UINT32_C(0x40580000));
  CHECK(float_bits(b / a) == UINT32_C(0x3fc00000));
  CHECK(float_bits(-a) == UINT32_C(0xbfc00000));

  CHECK(float_bits(zero + neg_zero) == UINT32_C(0));
  CHECK(float_bits(neg_zero + neg_zero) == UINT32_C(0x80000000));
  CHECK(float_bits(neg_zero * b) == UINT32_C(0x80000000));
  CHECK(float_bits(a / zero) == UINT32_C(0x7f800000));
  CHECK(float_bits(-a / zero) == UINT32_C(0xff800000));
  CHECK(float_bits_are_nan(float_bits(inf + neg_inf)));
  CHECK(float_bits_are_nan(float_bits(qnan + a)));
#if !defined(__ISA_NATIVE__)
  CHECK(float_bits(inf + neg_inf) == UINT32_C(0x7fc00000));
  CHECK(float_bits(qnan + a) == UINT32_C(0x7fc00000));
#endif
  CHECK(float_bits(min_subnormal + min_subnormal) == UINT32_C(2));

  CHECK(a < b && a <= b && b > a && b >= a && a != b);
  CHECK(!(qnan == qnan));
  CHECK(qnan != qnan);
  CHECK(!(qnan < a) && !(qnan <= a));
  CHECK(!(qnan > a) && !(qnan >= a));
}

static void test_double_arithmetic(void)
{
  volatile double a = 1.5;
  volatile double b = 2.25;
  volatile double zero = 0.0;
  volatile double neg_zero =
      double_from_bits(UINT64_C(0x8000000000000000));
  volatile double inf = double_from_bits(UINT64_C(0x7ff0000000000000));
  volatile double neg_inf =
      double_from_bits(UINT64_C(0xfff0000000000000));
  volatile double qnan = double_from_bits(UINT64_C(0x7ff8123456789abc));
  volatile double min_subnormal = double_from_bits(UINT64_C(1));

  CHECK(double_bits(a + b) == UINT64_C(0x400e000000000000));
  CHECK(double_bits(b - a) == UINT64_C(0x3fe8000000000000));
  CHECK(double_bits(a * b) == UINT64_C(0x400b000000000000));
  CHECK(double_bits(b / a) == UINT64_C(0x3ff8000000000000));
  CHECK(double_bits(-a) == UINT64_C(0xbff8000000000000));

  CHECK(double_bits(zero + neg_zero) == UINT64_C(0));
  CHECK(double_bits(neg_zero + neg_zero) ==
        UINT64_C(0x8000000000000000));
  CHECK(double_bits(neg_zero * b) == UINT64_C(0x8000000000000000));
  CHECK(double_bits(a / zero) == UINT64_C(0x7ff0000000000000));
  CHECK(double_bits(-a / zero) == UINT64_C(0xfff0000000000000));
  CHECK(double_bits_are_nan(double_bits(inf + neg_inf)));
  CHECK(double_bits_are_nan(double_bits(qnan + a)));
#if !defined(__ISA_NATIVE__)
  CHECK(double_bits(inf + neg_inf) == UINT64_C(0x7ff8000000000000));
  CHECK(double_bits(qnan + a) == UINT64_C(0x7ff8000000000000));
#endif
  CHECK(double_bits(min_subnormal + min_subnormal) == UINT64_C(2));

  CHECK(a < b && a <= b && b > a && b >= a && a != b);
  CHECK(!(qnan == qnan));
  CHECK(qnan != qnan);
  CHECK(!(qnan < a) && !(qnan <= a));
  CHECK(!(qnan > a) && !(qnan >= a));
}

static void test_conversions(void)
{
  volatile int signed_value = -1234567;
  volatile unsigned int unsigned_value = UINT32_C(3000000000);
  volatile long long signed_wide = -INT64_C(1234567890123);
  volatile unsigned long long unsigned_wide = UINT64_C(1234567890123);

  volatile float from_int = signed_value;
  volatile float from_unsigned = unsigned_value;
  volatile float from_wide = signed_wide;
  volatile float from_unsigned_wide = unsigned_wide;
  volatile double double_from_int = signed_value;
  volatile double double_from_unsigned = unsigned_value;
  volatile double double_from_wide = signed_wide;
  volatile double double_from_unsigned_wide = unsigned_wide;

  CHECK(float_bits(from_int) == UINT32_C(0xc996b438));
  CHECK(float_bits(from_unsigned) == UINT32_C(0x4f32d05e));
  CHECK(float_bits(from_wide) == UINT32_C(0xd38fb8fe));
  CHECK(float_bits(from_unsigned_wide) == UINT32_C(0x538fb8fe));
  CHECK(double_bits(double_from_int) == UINT64_C(0xc132d68700000000));
  CHECK(double_bits(double_from_unsigned) == UINT64_C(0x41e65a0bc0000000));
  CHECK(double_bits(double_from_wide) == UINT64_C(0xc271f71fb04cb000));
  CHECK(double_bits(double_from_unsigned_wide) ==
        UINT64_C(0x4271f71fb04cb000));

  volatile float fractional_float = -12345.75f;
  volatile double fractional_double = -123456789.875;
  volatile float positive_float = 4000000000.0f;
  volatile double positive_double = 4000000000.0;
  volatile double positive_wide_double = 1234567890123.0;

  CHECK((int)fractional_float == -12345);
  CHECK((long long)fractional_float == -12345);
  CHECK((int)fractional_double == -123456789);
  CHECK((long long)fractional_double == -123456789);
  CHECK((unsigned int)positive_float == UINT32_C(4000000000));
  CHECK((unsigned int)positive_double == UINT32_C(4000000000));
  CHECK((unsigned long long)positive_double == UINT64_C(4000000000));
  CHECK((unsigned long long)positive_wide_double ==
        UINT64_C(1234567890123));

  volatile float exact_float = 1.5f;
  volatile double widened = exact_float;
  volatile double narrowed_source = 1.0 + 0x1p-24;
  volatile float narrowed = narrowed_source;
  CHECK(double_bits(widened) == UINT64_C(0x3ff8000000000000));
  CHECK(float_bits(narrowed) == UINT32_C(0x3f800000));

  volatile int round_input = 16777217;
  volatile long long wide_round_input = INT64_C(9007199254740993);
  CHECK(float_bits((float)round_input) == UINT32_C(0x4b800000));
  CHECK(double_bits((double)wide_round_input) ==
        UINT64_C(0x4340000000000000));
}

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
static void test_direct_abi_entry_points(void)
{
  volatile float fa = 1.0f;
  volatile float fb = 2.0f;
  volatile float fnan = float_from_bits(UINT32_C(0x7fc00001));
  volatile double da = 1.0;
  volatile double db = 2.0;
  volatile double dnan = double_from_bits(UINT64_C(0x7ff8000000000001));

  CHECK(float_bits(__negsf2(fa)) == UINT32_C(0xbf800000));
  CHECK(double_bits(__negdf2(da)) == UINT64_C(0xbff0000000000000));
  CHECK(__fixunssfdi(4000000000.0f) == UINT64_C(4000000000));
  CHECK(__fixunsdfdi(1234567890123.0) == UINT64_C(1234567890123));

  CHECK(__cmpsf2(fa, fb) < 0 && __cmpsf2(fb, fa) > 0);
  CHECK(__lesf2(fa, fb) < 0 && __lesf2(fa, fa) == 0);
  CHECK(__gesf2(fb, fa) > 0 && __gesf2(fa, fa) == 0);
  CHECK(__eqsf2(fa, fa) == 0 && __nesf2(fa, fb) != 0);
  CHECK(__cmpsf2(fnan, fa) > 0 && __gesf2(fnan, fa) < 0);
  CHECK(__unordsf2(fnan, fa) != 0 && __unordsf2(fa, fb) == 0);

  CHECK(__cmpdf2(da, db) < 0 && __cmpdf2(db, da) > 0);
  CHECK(__ledf2(da, db) < 0 && __ledf2(da, da) == 0);
  CHECK(__gedf2(db, da) > 0 && __gedf2(da, da) == 0);
  CHECK(__eqdf2(da, da) == 0 && __nedf2(da, db) != 0);
  CHECK(__cmpdf2(dnan, da) > 0 && __gedf2(dnan, da) < 0);
  CHECK(__unorddf2(dnan, da) != 0 && __unorddf2(da, db) == 0);
}
#endif

static void test_foundational_math(void)
{
  volatile double positive = 3.75;
  volatile double negative = -3.75;
  volatile double positive_half = 2.5;
  volatile double negative_half = -2.5;
  volatile double positive_zero = 0.0;
  volatile double negative_zero =
      double_from_bits(UINT64_C(0x8000000000000000));
  volatile double min_subnormal = double_from_bits(UINT64_C(1));
  volatile double negative_min_subnormal =
      double_from_bits(UINT64_C(0x8000000000000001));
  volatile double infinity =
      double_from_bits(UINT64_C(0x7ff0000000000000));
  volatile double negative_infinity =
      double_from_bits(UINT64_C(0xfff0000000000000));
  volatile double qnan = double_from_bits(UINT64_C(0x7ff8123456789abc));

  CHECK(double_bits(floor(positive)) == UINT64_C(0x4008000000000000));
  CHECK(double_bits(floor(negative)) == UINT64_C(0xc010000000000000));
  CHECK(double_bits(ceil(positive)) == UINT64_C(0x4010000000000000));
  CHECK(double_bits(ceil(negative)) == UINT64_C(0xc008000000000000));
  CHECK(double_bits(trunc(positive)) == UINT64_C(0x4008000000000000));
  CHECK(double_bits(trunc(negative)) == UINT64_C(0xc008000000000000));
  CHECK(double_bits(round(positive_half)) == UINT64_C(0x4008000000000000));
  CHECK(double_bits(round(negative_half)) == UINT64_C(0xc008000000000000));

  CHECK(double_bits(floor(negative_zero)) ==
        UINT64_C(0x8000000000000000));
  CHECK(double_bits(ceil(negative_zero)) ==
        UINT64_C(0x8000000000000000));
  CHECK(double_bits(round(negative_zero)) ==
        UINT64_C(0x8000000000000000));
  CHECK(double_bits(trunc(negative_zero)) ==
        UINT64_C(0x8000000000000000));
  CHECK(double_bits(floor(min_subnormal)) == UINT64_C(0));
  CHECK(double_bits(ceil(min_subnormal)) == UINT64_C(0x3ff0000000000000));
  CHECK(double_bits(trunc(negative_min_subnormal)) ==
        UINT64_C(0x8000000000000000));
  CHECK(double_bits(floor(negative_min_subnormal)) ==
        UINT64_C(0xbff0000000000000));
  CHECK(double_bits(floor(infinity)) == UINT64_C(0x7ff0000000000000));
  CHECK(double_bits_are_nan(double_bits(round(qnan))));

  CHECK(double_bits(sqrt(positive_zero)) == UINT64_C(0));
  CHECK(double_bits(sqrt(negative_zero)) ==
        UINT64_C(0x8000000000000000));
  CHECK(double_bits(sqrt(4.0)) == UINT64_C(0x4000000000000000));
  CHECK(double_bits(sqrt(2.0)) == UINT64_C(0x3ff6a09e667f3bcd));
  CHECK(double_bits(sqrt(min_subnormal)) ==
        UINT64_C(0x1e60000000000000));
  CHECK(double_bits(sqrt(infinity)) == UINT64_C(0x7ff0000000000000));
  CHECK(double_bits_are_nan(double_bits(sqrt(qnan))));

  errno = 0;
  CHECK(double_bits_are_nan(double_bits(sqrt(-1.0))));
  CHECK(errno == EDOM);
  errno = 0;
  CHECK(double_bits_are_nan(double_bits(sqrt(negative_infinity))));
  CHECK(errno == EDOM);
  errno = 0;
  CHECK(double_bits_are_nan(double_bits(sqrt(qnan))));
  CHECK(errno == 0);

  CHECK(double_bits(fmod(5.3, 2.0)) == UINT64_C(0x3ff4cccccccccccc));
  CHECK(double_bits(fmod(-5.3, 2.0)) == UINT64_C(0xbff4cccccccccccc));
  CHECK(double_bits(fmod(6.0, 4.0)) == UINT64_C(0x4000000000000000));
  CHECK(double_bits(fmod(2.0, -6.0)) == UINT64_C(0x4000000000000000));
  CHECK(double_bits(fmod(-4.0, 2.0)) ==
        UINT64_C(0x8000000000000000));
  CHECK(double_bits(fmod(min_subnormal, 1.0)) == UINT64_C(1));
  CHECK(double_bits(fmod(1.0, min_subnormal)) == UINT64_C(0));
  CHECK(double_bits(fmod(double_from_bits(UINT64_C(0x7fefffffffffffff)),
                         3.0)) == UINT64_C(0x4000000000000000));
  CHECK(double_bits(fmod(positive, infinity)) == double_bits(positive));

  errno = 0;
  CHECK(double_bits_are_nan(double_bits(fmod(positive, positive_zero))));
  CHECK(errno == EDOM);
  errno = 0;
  CHECK(double_bits_are_nan(double_bits(fmod(infinity, positive))));
  CHECK(errno == EDOM);
  errno = 0;
  CHECK(double_bits_are_nan(double_bits(fmod(qnan, positive))));
  CHECK(errno == 0);

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
  CHECK(double_bits(round(qnan)) == UINT64_C(0x7ff8000000000000));
  CHECK(double_bits(sqrt(qnan)) == UINT64_C(0x7ff8000000000000));
  CHECK(double_bits(fmod(qnan, positive)) == UINT64_C(0x7ff8000000000000));
  CHECK(double_bits(fmod(infinity, positive)) ==
        UINT64_C(0x7ff8000000000000));
#endif
}

static void test_classification_and_scaling(void)
{
  const float f_pos_zero = float_from_bits(UINT32_C(0));
  const float f_neg_zero = float_from_bits(UINT32_C(0x80000000));
  const float f_subnormal = float_from_bits(UINT32_C(1));
  const float f_min_normal = float_from_bits(UINT32_C(0x00800000));
  const float f_infinity = float_from_bits(UINT32_C(0x7f800000));
  const float f_nan = float_from_bits(UINT32_C(0x7fc12345));
  const float f_signaling_nan = float_from_bits(UINT32_C(0x7f800001));
  const double d_pos_zero = double_from_bits(UINT64_C(0));
  const double d_neg_zero =
      double_from_bits(UINT64_C(0x8000000000000000));
  const double d_subnormal = double_from_bits(UINT64_C(1));
  const double d_min_normal =
      double_from_bits(UINT64_C(0x0010000000000000));
  const double d_infinity =
      double_from_bits(UINT64_C(0x7ff0000000000000));
  const double d_nan =
      double_from_bits(UINT64_C(0x7ff8123456789abc));
  const double d_signaling_nan =
      double_from_bits(UINT64_C(0x7ff0000000000001));

  float_t evaluation_float = 1.0f;
  double_t evaluation_double = 1.0;
  CHECK(sizeof(evaluation_float) >= sizeof(float));
  CHECK(sizeof(evaluation_double) >= sizeof(double));
  CHECK(isinf(HUGE_VAL) && isinf(HUGE_VALF) && isinf(HUGE_VALL));
  CHECK(isinf(INFINITY) && isnan(NAN));
  CHECK(MATH_ERRNO == 1 && MATH_ERREXCEPT == 2);
#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
  CHECK(math_errhandling == MATH_ERRNO);
#else
  CHECK((math_errhandling & (MATH_ERRNO | MATH_ERREXCEPT)) != 0);
#endif

  CHECK(fpclassify(f_pos_zero) == FP_ZERO);
  CHECK(fpclassify(f_neg_zero) == FP_ZERO && signbit(f_neg_zero));
  CHECK(fpclassify(f_subnormal) == FP_SUBNORMAL);
  CHECK(fpclassify(f_min_normal) == FP_NORMAL);
  CHECK(fpclassify(1.0f) == FP_NORMAL);
  CHECK(fpclassify(f_infinity) == FP_INFINITE);
  CHECK(fpclassify(f_nan) == FP_NAN);
  CHECK(isfinite(f_subnormal) && !isfinite(f_infinity));
  CHECK(isinf(f_infinity) && !isinf(1.0f));
  CHECK(isnan(f_nan) && !isnan(f_infinity));
  CHECK(isnormal(f_min_normal) && !isnormal(f_subnormal));

  CHECK(fpclassify(d_pos_zero) == FP_ZERO);
  CHECK(fpclassify(d_neg_zero) == FP_ZERO && signbit(d_neg_zero));
  CHECK(fpclassify(d_subnormal) == FP_SUBNORMAL);
  CHECK(fpclassify(d_min_normal) == FP_NORMAL);
  CHECK(fpclassify(1.0) == FP_NORMAL);
  CHECK(fpclassify(d_infinity) == FP_INFINITE);
  CHECK(fpclassify(d_nan) == FP_NAN);
  CHECK(isfinite(d_subnormal) && !isfinite(d_infinity));
  CHECK(isinf(d_infinity) && !isinf(1.0));
  CHECK(isnan(d_nan) && !isnan(d_infinity));
  CHECK(isnormal(d_min_normal) && !isnormal(d_subnormal));

  CHECK(fpclassify(0.0L) == FP_ZERO);
  CHECK(fpclassify(-0.0L) == FP_ZERO && signbit(-0.0L));
  CHECK(fpclassify(1.0L) == FP_NORMAL);
  CHECK(fpclassify(__LDBL_DENORM_MIN__) == FP_SUBNORMAL);
  CHECK(fpclassify(__builtin_infl()) == FP_INFINITE);
  CHECK(fpclassify(__builtin_nanl("")) == FP_NAN);

  const long double payload_nan = __builtin_nanl("0x1234");
  const long double negative_payload_nan = copysignl(payload_nan, -1.0L);
  CHECK(isnan(negative_payload_nan) && signbit(negative_payload_nan));
  CHECK(long_double_same_magnitude(payload_nan, negative_payload_nan));
  CHECK(!signbit(fabsl(-0.0L)));
  CHECK(signbit(copysignl(1.0L, -0.0L)));

  classification_evaluations = 0;
  CHECK(isfinite(counted_float(1.0f)) && classification_evaluations == 1);
  classification_evaluations = 0;
  CHECK(fpclassify(counted_double(d_nan)) == FP_NAN &&
        classification_evaluations == 1);
  classification_evaluations = 0;
  CHECK(signbit(counted_long_double(-0.0L)) &&
        classification_evaluations == 1);

  CHECK(float_bits(copysignf(f_nan, -1.0f)) == UINT32_C(0xffc12345));
  CHECK(float_bits(copysignf(f_neg_zero, 1.0f)) == UINT32_C(0));
  CHECK(double_bits(copysign(d_nan, -1.0)) ==
        UINT64_C(0xfff8123456789abc));
  CHECK(double_bits(copysign(d_neg_zero, 1.0)) == UINT64_C(0));
#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
  softfloat_exceptionFlags = 0;
  CHECK(fpclassify(f_signaling_nan) == FP_NAN);
  CHECK(fpclassify(d_signaling_nan) == FP_NAN);
  CHECK(float_bits(copysignf(f_signaling_nan, -1.0f)) ==
        UINT32_C(0xff800001));
  CHECK(double_bits(copysign(d_signaling_nan, -1.0)) ==
        UINT64_C(0xfff0000000000001));
  CHECK((softfloat_exceptionFlags & TEST_SOFTFLOAT_INVALID) == 0);
#endif

  int exponent = 99;
  CHECK(double_bits(frexp(12.0, &exponent)) ==
        UINT64_C(0x3fe8000000000000));
  CHECK(exponent == 4);
  CHECK(double_bits(frexp(-12.0, &exponent)) ==
        UINT64_C(0xbfe8000000000000));
  CHECK(exponent == 4);
  CHECK(double_bits(frexp(d_subnormal, &exponent)) ==
        UINT64_C(0x3fe0000000000000));
  CHECK(exponent == -1073);
  CHECK(double_bits(frexp(d_neg_zero, &exponent)) ==
        UINT64_C(0x8000000000000000));
  CHECK(exponent == 0);
  CHECK(double_bits(frexp(d_infinity, &exponent)) ==
        UINT64_C(0x7ff0000000000000));
  CHECK(exponent == 0);

  CHECK(float_bits(frexpf(12.0f, &exponent)) == UINT32_C(0x3f400000));
  CHECK(exponent == 4);
  CHECK(float_bits(frexpf(f_subnormal, &exponent)) ==
        UINT32_C(0x3f000000));
  CHECK(exponent == -148);
  CHECK(float_bits(frexpf(f_neg_zero, &exponent)) ==
        UINT32_C(0x80000000));
  CHECK(exponent == 0);

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
  softfloat_exceptionFlags = 0;
  CHECK(double_bits(frexp(d_signaling_nan, &exponent)) ==
        UINT64_C(0x7ff8000000000000));
  CHECK(exponent == 0 &&
        (softfloat_exceptionFlags & TEST_SOFTFLOAT_INVALID) != 0);
  softfloat_exceptionFlags = 0;
  CHECK(float_bits(frexpf(f_signaling_nan, &exponent)) ==
        UINT32_C(0x7fc00000));
  CHECK(exponent == 0 &&
        (softfloat_exceptionFlags & TEST_SOFTFLOAT_INVALID) != 0);
#endif

  errno = 0;
  CHECK(double_bits(ldexp(0.75, 4)) == UINT64_C(0x4028000000000000));
  CHECK(errno == 0);
  CHECK(double_bits(scalbn(1.0, -1074)) == UINT64_C(1));
  CHECK(double_bits(scalbn(d_subnormal, 1)) == UINT64_C(2));
  CHECK(double_bits(scalbn(double_from_bits(UINT64_C(0x0010000000000001)),
                           -1)) == UINT64_C(0x0008000000000000));
  CHECK(double_bits(scalbn(double_from_bits(UINT64_C(0x0010000000000003)),
                           -1)) == UINT64_C(0x0008000000000002));
  errno = 0;
  CHECK(double_bits(scalbn(1.0, -1075)) == UINT64_C(0));
  CHECK(errno == ERANGE);
  errno = 0;
  CHECK(double_bits(scalbn(-1.0, INT_MIN)) ==
        UINT64_C(0x8000000000000000));
  CHECK(errno == ERANGE);
  errno = 0;
  CHECK(double_bits(scalbn(double_from_bits(UINT64_C(0x7fefffffffffffff)),
                           1)) == UINT64_C(0x7ff0000000000000));
  CHECK(errno == ERANGE);

  errno = 0;
  CHECK(float_bits(ldexpf(0.75f, 4)) == UINT32_C(0x41400000));
  CHECK(errno == 0);
  CHECK(float_bits(scalbnf(1.0f, -149)) == UINT32_C(1));
  CHECK(float_bits(scalbnf(f_subnormal, 1)) == UINT32_C(2));
  CHECK(float_bits(scalbnf(float_from_bits(UINT32_C(0x00800001)), -1)) ==
        UINT32_C(0x00400000));
  CHECK(float_bits(scalbnf(float_from_bits(UINT32_C(0x00800003)), -1)) ==
        UINT32_C(0x00400002));
  errno = 0;
  CHECK(float_bits(scalbnf(1.0f, -150)) == UINT32_C(0));
  CHECK(errno == ERANGE);
  errno = 0;
  CHECK(float_bits(scalbnf(float_from_bits(UINT32_C(0x7f7fffff)), 1)) ==
        UINT32_C(0x7f800000));
  CHECK(errno == ERANGE);

  double integer_part;
  double fraction = modf(3.75, &integer_part);
  CHECK(double_bits(integer_part) == UINT64_C(0x4008000000000000));
  CHECK(double_bits(fraction) == UINT64_C(0x3fe8000000000000));
  fraction = modf(-3.75, &integer_part);
  CHECK(double_bits(integer_part) == UINT64_C(0xc008000000000000));
  CHECK(double_bits(fraction) == UINT64_C(0xbfe8000000000000));
  fraction = modf(-0.25, &integer_part);
  CHECK(double_bits(integer_part) == UINT64_C(0x8000000000000000));
  CHECK(double_bits(fraction) == UINT64_C(0xbfd0000000000000));
  fraction = modf(-2.0, &integer_part);
  CHECK(double_bits(integer_part) == UINT64_C(0xc000000000000000));
  CHECK(double_bits(fraction) == UINT64_C(0x8000000000000000));
  fraction = modf(-d_infinity, &integer_part);
  CHECK(double_bits(integer_part) == UINT64_C(0xfff0000000000000));
  CHECK(double_bits(fraction) == UINT64_C(0x8000000000000000));
  fraction = modf(d_nan, &integer_part);
  CHECK(double_bits(integer_part) == double_bits(d_nan));
  CHECK(double_bits(fraction) == double_bits(d_nan));

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
  softfloat_exceptionFlags = 0;
  fraction = modf(d_signaling_nan, &integer_part);
  CHECK(double_bits(integer_part) == UINT64_C(0x7ff8000000000000));
  CHECK(double_bits(fraction) == UINT64_C(0x7ff8000000000000));
  CHECK((softfloat_exceptionFlags & TEST_SOFTFLOAT_INVALID) != 0);
#endif

  float integer_part_f;
  float fraction_f = modff(-3.75f, &integer_part_f);
  CHECK(float_bits(integer_part_f) == UINT32_C(0xc0400000));
  CHECK(float_bits(fraction_f) == UINT32_C(0xbf400000));
  fraction_f = modff(f_infinity, &integer_part_f);
  CHECK(float_bits(integer_part_f) == UINT32_C(0x7f800000));
  CHECK(float_bits(fraction_f) == UINT32_C(0));

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
  softfloat_exceptionFlags = 0;
  fraction_f = modff(f_signaling_nan, &integer_part_f);
  CHECK(float_bits(integer_part_f) == UINT32_C(0x7fc00000));
  CHECK(float_bits(fraction_f) == UINT32_C(0x7fc00000));
  CHECK((softfloat_exceptionFlags & TEST_SOFTFLOAT_INVALID) != 0);
#endif

  errno = 0;
  CHECK(double_bits(nextafter(1.0, 2.0)) ==
        UINT64_C(0x3ff0000000000001));
  CHECK(errno == 0);
  CHECK(double_bits(nextafter(1.0, 0.0)) ==
        UINT64_C(0x3fefffffffffffff));
  CHECK(double_bits(nextafter(-1.0, -2.0)) ==
        UINT64_C(0xbff0000000000001));
  CHECK(double_bits(nextafter(d_pos_zero, d_neg_zero)) ==
        UINT64_C(0x8000000000000000));
  errno = 0;
#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
  softfloat_exceptionFlags = 0;
#endif
  CHECK(double_bits(nextafter(d_pos_zero, -1.0)) ==
        UINT64_C(0x8000000000000001));
#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
  CHECK(errno == ERANGE);
  CHECK((softfloat_exceptionFlags &
         (TEST_SOFTFLOAT_UNDERFLOW | TEST_SOFTFLOAT_INEXACT)) ==
        (TEST_SOFTFLOAT_UNDERFLOW | TEST_SOFTFLOAT_INEXACT));
#else
  CHECK(errno == 0);
#endif
  errno = 0;
  CHECK(double_bits(nextafter(d_subnormal, d_pos_zero)) == UINT64_C(0));
  CHECK(errno == ERANGE);
  errno = 0;
  CHECK(double_bits(nextafter(d_min_normal, d_pos_zero)) ==
        UINT64_C(0x000fffffffffffff));
  CHECK(errno == ERANGE);
  errno = 0;
#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
  softfloat_exceptionFlags = 0;
#endif
  CHECK(double_bits(nextafter(double_from_bits(UINT64_C(0x7fefffffffffffff)),
                              d_infinity)) ==
        UINT64_C(0x7ff0000000000000));
  CHECK(errno == ERANGE);
#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
  CHECK((softfloat_exceptionFlags &
         (TEST_SOFTFLOAT_OVERFLOW | TEST_SOFTFLOAT_INEXACT)) ==
        (TEST_SOFTFLOAT_OVERFLOW | TEST_SOFTFLOAT_INEXACT));
#endif
  errno = 0;
  CHECK(double_bits(nextafter(d_infinity, d_pos_zero)) ==
        UINT64_C(0x7fefffffffffffff));
  CHECK(errno == 0);

  errno = 0;
  CHECK(float_bits(nextafterf(1.0f, 2.0f)) == UINT32_C(0x3f800001));
  CHECK(float_bits(nextafterf(f_pos_zero, -1.0f)) ==
        UINT32_C(0x80000001));
#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
  CHECK(errno == ERANGE);
#else
  CHECK(errno == 0);
#endif
  errno = 0;
  CHECK(float_bits(nextafterf(f_min_normal, f_pos_zero)) ==
        UINT32_C(0x007fffff));
  CHECK(errno == ERANGE);

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
  CHECK(double_bits(scalbn(d_nan, 12)) == UINT64_C(0x7ff8000000000000));
  CHECK(float_bits(scalbnf(f_nan, 12)) == UINT32_C(0x7fc00000));
  CHECK(double_bits(nextafter(d_nan, 1.0)) ==
        UINT64_C(0x7ff8000000000000));
  CHECK(float_bits(nextafterf(f_nan, 1.0f)) == UINT32_C(0x7fc00000));
#endif
}

int main()
{
  test_float_arithmetic();
  test_double_arithmetic();
  test_conversions();
#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
  test_direct_abi_entry_points();
#endif
  test_foundational_math();
  test_classification_and_scaling();

  printf(failures == 0 ? "SOFTFLOAT TEST PASS\n"
                       : "SOFTFLOAT TEST FAIL, failures=%d\n",
         failures);
  halt(failures == 0 ? 0 : 1);
  return failures == 0 ? 0 : 1;
}
