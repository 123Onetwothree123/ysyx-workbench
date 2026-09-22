#include <am.h>
#include <klib.h>
#include <klib-macros.h>
#include <math.h>
#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
#include <errno.h>

#include "softfloat/softfloat.h"

#define F64_SIGN_MASK UINT64_C(0x8000000000000000)
#define F64_EXP_MASK UINT64_C(0x7ff0000000000000)
#define F64_FRAC_MASK UINT64_C(0x000fffffffffffff)
#define F64_HIDDEN_BIT UINT64_C(0x0010000000000000)
#define F64_QUIET_BIT UINT64_C(0x0008000000000000)
#define F32_SIGN_MASK UINT32_C(0x80000000)
#define F32_EXP_MASK UINT32_C(0x7f800000)
#define F32_FRAC_MASK UINT32_C(0x007fffff)
#define F32_HIDDEN_BIT UINT32_C(0x00800000)
#define F32_QUIET_BIT UINT32_C(0x00400000)

static uint32_t float_to_bits(float value)
{
  uint32_t bits;
  memcpy(&bits, &value, sizeof(bits));
  return bits;
}

static float float_from_bits(uint32_t bits)
{
  float value;
  memcpy(&value, &bits, sizeof(value));
  return value;
}

static uint64_t double_to_bits(double value)
{
  uint64_t bits;
  memcpy(&bits, &value, sizeof(bits));
  return bits;
}

static double double_from_bits(uint64_t bits)
{
  double value;
  memcpy(&value, &bits, sizeof(value));
  return value;
}

static float64_t double_to_softfloat(double value)
{
  return (float64_t){.v = double_to_bits(value)};
}

static double double_from_softfloat(float64_t value)
{
  return double_from_bits(value.v);
}

static int f64_magnitude_is_nan(uint64_t magnitude)
{
  return magnitude > F64_EXP_MASK;
}

static int f64_magnitude_is_signaling_nan(uint64_t magnitude)
{
  return f64_magnitude_is_nan(magnitude) && !(magnitude & F64_QUIET_BIT);
}

static int f32_magnitude_is_nan(uint32_t magnitude)
{
  return magnitude > F32_EXP_MASK;
}

static int f32_magnitude_is_signaling_nan(uint32_t magnitude)
{
  return f32_magnitude_is_nan(magnitude) && !(magnitude & F32_QUIET_BIT);
}

static int classify_f64_bits(uint64_t bits)
{
  const uint64_t magnitude = bits & ~F64_SIGN_MASK;
  const uint64_t exponent = magnitude & F64_EXP_MASK;
  if (exponent == F64_EXP_MASK)
  {
    return magnitude == F64_EXP_MASK ? FP_INFINITE : FP_NAN;
  }
  if (exponent != 0) return FP_NORMAL;
  return magnitude == 0 ? FP_ZERO : FP_SUBNORMAL;
}

static int classify_f32_bits(uint32_t bits)
{
  const uint32_t magnitude = bits & ~F32_SIGN_MASK;
  const uint32_t exponent = magnitude & F32_EXP_MASK;
  if (exponent == F32_EXP_MASK)
  {
    return magnitude == F32_EXP_MASK ? FP_INFINITE : FP_NAN;
  }
  if (exponent != 0) return FP_NORMAL;
  return magnitude == 0 ? FP_ZERO : FP_SUBNORMAL;
}

static double canonical_nan(void)
{
  return double_from_bits(UINT64_C(0x7ff8000000000000));
}

static float canonical_nanf(void)
{
  return float_from_bits(UINT32_C(0x7fc00000));
}

int __klib_fpclassifyf(float x)
{
  return classify_f32_bits(float_to_bits(x));
}

int __klib_fpclassify(double x)
{
  return classify_f64_bits(double_to_bits(x));
}

int __klib_signbitf(float x)
{
  return (int)(float_to_bits(x) >> 31);
}

int __klib_signbit(double x)
{
  return (int)(double_to_bits(x) >> 63);
}

int __klib_fpclassifyl(long double x)
{
#if __LDBL_MANT_DIG__ == 113 && __SIZEOF_LONG_DOUBLE__ == 16
  uint64_t words[2];
  memcpy(words, &x, sizeof(x));
#if __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
  const uint64_t high = words[1];
  const uint64_t low = words[0];
#else
  const uint64_t high = words[0];
  const uint64_t low = words[1];
#endif
  const unsigned exponent = (unsigned)((high >> 48) & UINT64_C(0x7fff));
  const uint64_t fraction_high = high & UINT64_C(0x0000ffffffffffff);
  if (exponent == 0x7fff)
  {
    return (fraction_high | low) == 0 ? FP_INFINITE : FP_NAN;
  }
  if (exponent != 0) return FP_NORMAL;
  return (fraction_high | low) == 0 ? FP_ZERO : FP_SUBNORMAL;
#elif __LDBL_MANT_DIG__ == 64 && \
      (__SIZEOF_LONG_DOUBLE__ == 12 || __SIZEOF_LONG_DOUBLE__ == 16) && \
      __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
  unsigned char bytes[16];
  uint64_t significand;
  uint16_t sign_exponent;
  memcpy(bytes, &x, sizeof(x));
  memcpy(&significand, bytes, sizeof(significand));
  memcpy(&sign_exponent, bytes + 8, sizeof(sign_exponent));
  const unsigned exponent = sign_exponent & UINT16_C(0x7fff);
  if (exponent == 0x7fff)
  {
    return significand == UINT64_C(0x8000000000000000) ? FP_INFINITE
                                                        : FP_NAN;
  }
  if (exponent != 0) return FP_NORMAL;
  return significand == 0 ? FP_ZERO : FP_SUBNORMAL;
#elif __LDBL_MANT_DIG__ == 53 && __SIZEOF_LONG_DOUBLE__ == 8
  uint64_t bits;
  memcpy(&bits, &x, sizeof(bits));
  return classify_f64_bits(bits);
#else
#error "KLIB math: unsupported long double representation"
#endif
}

int __klib_signbitl(long double x)
{
#if __LDBL_MANT_DIG__ == 113 && __SIZEOF_LONG_DOUBLE__ == 16
  uint64_t words[2];
  memcpy(words, &x, sizeof(x));
#if __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
  return (int)(words[1] >> 63);
#else
  return (int)(words[0] >> 63);
#endif
#elif __LDBL_MANT_DIG__ == 64 && \
      (__SIZEOF_LONG_DOUBLE__ == 12 || __SIZEOF_LONG_DOUBLE__ == 16) && \
      __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
  unsigned char bytes[16];
  uint16_t sign_exponent;
  memcpy(bytes, &x, sizeof(x));
  memcpy(&sign_exponent, bytes + 8, sizeof(sign_exponent));
  return (int)(sign_exponent >> 15);
#elif __LDBL_MANT_DIG__ == 53 && __SIZEOF_LONG_DOUBLE__ == 8
  uint64_t bits;
  memcpy(&bits, &x, sizeof(bits));
  return (int)(bits >> 63);
#else
#error "KLIB math: unsupported long double representation"
#endif
}

static long double long_double_with_sign(long double value, int negative)
{
  unsigned char bytes[sizeof(long double)];
  memcpy(bytes, &value, sizeof(bytes));

#if __LDBL_MANT_DIG__ == 113 && __SIZEOF_LONG_DOUBLE__ == 16
#if __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
  const size_t sign_byte = 15;
#else
  const size_t sign_byte = 0;
#endif
#elif __LDBL_MANT_DIG__ == 64 && \
      (__SIZEOF_LONG_DOUBLE__ == 12 || __SIZEOF_LONG_DOUBLE__ == 16) && \
      __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
  const size_t sign_byte = 9;
#elif __LDBL_MANT_DIG__ == 53 && __SIZEOF_LONG_DOUBLE__ == 8
#if __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
  const size_t sign_byte = 7;
#else
  const size_t sign_byte = 0;
#endif
#else
#error "KLIB math: unsupported long double representation"
#endif

  bytes[sign_byte] = (unsigned char)((bytes[sign_byte] & 0x7f) |
                                     (negative ? 0x80 : 0));
  memcpy(&value, bytes, sizeof(value));
  return value;
}

double fabs(double x)
{
  return double_from_bits(double_to_bits(x) & ~F64_SIGN_MASK);
}
float fabsf(float x)
{
  return float_from_bits(float_to_bits(x) & ~F32_SIGN_MASK);
}
long double fabsl(long double x)
{
  return long_double_with_sign(x, 0);
}
double copysign(double x, double y)
{
  return double_from_bits((double_to_bits(x) & ~F64_SIGN_MASK) |
                          (double_to_bits(y) & F64_SIGN_MASK));
}
float copysignf(float x, float y)
{
  return float_from_bits((float_to_bits(x) & ~F32_SIGN_MASK) |
                         (float_to_bits(y) & F32_SIGN_MASK));
}
long double copysignl(long double x, long double y)
{
  return long_double_with_sign(x, __klib_signbitl(y));
}

double frexp(double x, int *exponent)
{
  const uint64_t bits = double_to_bits(x);
  const uint64_t magnitude = bits & ~F64_SIGN_MASK;
  const unsigned encoded_exponent = (unsigned)(magnitude >> 52);
  uint64_t mantissa = magnitude & F64_FRAC_MASK;

  if (encoded_exponent == 0x7ff)
  {
    *exponent = 0;
    if (f64_magnitude_is_signaling_nan(magnitude))
    {
      softfloat_raiseFlags(softfloat_flag_invalid);
      return canonical_nan();
    }
    return x;
  }
  if (magnitude == 0)
  {
    *exponent = 0;
    return x;
  }
  if (encoded_exponent != 0)
  {
    *exponent = (int)encoded_exponent - 1022;
  }
  else
  {
    int normalization = 0;
    while (!(mantissa & F64_HIDDEN_BIT))
    {
      mantissa <<= 1;
      normalization++;
    }
    *exponent = -1021 - normalization;
  }
  return double_from_bits((bits & F64_SIGN_MASK) |
                          (UINT64_C(1022) << 52) |
                          (mantissa & F64_FRAC_MASK));
}

float frexpf(float x, int *exponent)
{
  const uint32_t bits = float_to_bits(x);
  const uint32_t magnitude = bits & ~F32_SIGN_MASK;
  const unsigned encoded_exponent = magnitude >> 23;
  uint32_t mantissa = magnitude & F32_FRAC_MASK;

  if (encoded_exponent == 0xff)
  {
    *exponent = 0;
    if (f32_magnitude_is_signaling_nan(magnitude))
    {
      softfloat_raiseFlags(softfloat_flag_invalid);
      return canonical_nanf();
    }
    return x;
  }
  if (magnitude == 0)
  {
    *exponent = 0;
    return x;
  }
  if (encoded_exponent != 0)
  {
    *exponent = (int)encoded_exponent - 126;
  }
  else
  {
    int normalization = 0;
    while (!(mantissa & F32_HIDDEN_BIT))
    {
      mantissa <<= 1;
      normalization++;
    }
    *exponent = -125 - normalization;
  }
  return float_from_bits((bits & F32_SIGN_MASK) |
                         (UINT32_C(126) << 23) |
                         (mantissa & F32_FRAC_MASK));
}

static uint64_t round_shift_right_even(uint64_t value, int64_t shift,
                                       int *inexact)
{
  if (shift >= 64)
  {
    *inexact = value != 0;
    return 0;
  }

  const uint64_t truncated = value >> (unsigned)shift;
  const uint64_t mask = (UINT64_C(1) << (unsigned)shift) - 1;
  const uint64_t remainder = value & mask;
  const uint64_t halfway = UINT64_C(1) << ((unsigned)shift - 1);
  *inexact = remainder != 0;
  return truncated +
         (remainder > halfway ||
          (remainder == halfway && (truncated & UINT64_C(1))));
}

static double scale_double(double x, int exponent_delta)
{
  const uint64_t bits = double_to_bits(x);
  const uint64_t sign = bits & F64_SIGN_MASK;
  const uint64_t magnitude = bits & ~F64_SIGN_MASK;
  const unsigned encoded_exponent = (unsigned)(magnitude >> 52);
  uint64_t mantissa = magnitude & F64_FRAC_MASK;

  if (f64_magnitude_is_nan(magnitude))
  {
    if (f64_magnitude_is_signaling_nan(magnitude))
    {
      softfloat_raiseFlags(softfloat_flag_invalid);
    }
    return canonical_nan();
  }
  if (magnitude == 0 || magnitude == F64_EXP_MASK) return x;

  int value_exponent;
  if (encoded_exponent != 0)
  {
    mantissa |= F64_HIDDEN_BIT;
    value_exponent = (int)encoded_exponent - 1075;
  }
  else
  {
    value_exponent = -1074;
    while (!(mantissa & F64_HIDDEN_BIT))
    {
      mantissa <<= 1;
      value_exponent--;
    }
  }

  const int64_t scaled_exponent =
      (int64_t)value_exponent + (int64_t)exponent_delta;
  const int64_t biased_exponent = scaled_exponent + INT64_C(1075);
  if (biased_exponent >= 0x7ff)
  {
    errno = ERANGE;
    softfloat_raiseFlags(softfloat_flag_overflow | softfloat_flag_inexact);
    return double_from_bits(sign | F64_EXP_MASK);
  }
  if (biased_exponent > 0)
  {
    return double_from_bits(sign | ((uint64_t)biased_exponent << 52) |
                            (mantissa & F64_FRAC_MASK));
  }

  const int64_t shift = 1 - biased_exponent;
  int inexact;
  const uint64_t rounded = round_shift_right_even(mantissa, shift, &inexact);
  if (inexact)
  {
    uint_fast8_t flags = softfloat_flag_inexact;
    if (rounded < F64_HIDDEN_BIT) flags |= softfloat_flag_underflow;
    softfloat_raiseFlags(flags);
  }
  if (rounded == 0) errno = ERANGE;
  return double_from_bits(sign | rounded);
}

static float scale_float(float x, int exponent_delta)
{
  const uint32_t bits = float_to_bits(x);
  const uint32_t sign = bits & F32_SIGN_MASK;
  const uint32_t magnitude = bits & ~F32_SIGN_MASK;
  const unsigned encoded_exponent = magnitude >> 23;
  uint32_t mantissa = magnitude & F32_FRAC_MASK;

  if (f32_magnitude_is_nan(magnitude))
  {
    if (f32_magnitude_is_signaling_nan(magnitude))
    {
      softfloat_raiseFlags(softfloat_flag_invalid);
    }
    return canonical_nanf();
  }
  if (magnitude == 0 || magnitude == F32_EXP_MASK) return x;

  int value_exponent;
  if (encoded_exponent != 0)
  {
    mantissa |= F32_HIDDEN_BIT;
    value_exponent = (int)encoded_exponent - 150;
  }
  else
  {
    value_exponent = -149;
    while (!(mantissa & F32_HIDDEN_BIT))
    {
      mantissa <<= 1;
      value_exponent--;
    }
  }

  const int64_t scaled_exponent =
      (int64_t)value_exponent + (int64_t)exponent_delta;
  const int64_t biased_exponent = scaled_exponent + INT64_C(150);
  if (biased_exponent >= 0xff)
  {
    errno = ERANGE;
    softfloat_raiseFlags(softfloat_flag_overflow | softfloat_flag_inexact);
    return float_from_bits(sign | F32_EXP_MASK);
  }
  if (biased_exponent > 0)
  {
    return float_from_bits(sign | ((uint32_t)biased_exponent << 23) |
                           (mantissa & F32_FRAC_MASK));
  }

  const int64_t shift = 1 - biased_exponent;
  int inexact;
  const uint32_t rounded =
      (uint32_t)round_shift_right_even(mantissa, shift, &inexact);
  if (inexact)
  {
    uint_fast8_t flags = softfloat_flag_inexact;
    if (rounded < F32_HIDDEN_BIT) flags |= softfloat_flag_underflow;
    softfloat_raiseFlags(flags);
  }
  if (rounded == 0) errno = ERANGE;
  return float_from_bits(sign | rounded);
}

double ldexp(double x, int exponent)
{
  return scale_double(x, exponent);
}

float ldexpf(float x, int exponent)
{
  return scale_float(x, exponent);
}

double scalbn(double x, int exponent)
{
  return scale_double(x, exponent);
}

float scalbnf(float x, int exponent)
{
  return scale_float(x, exponent);
}

double modf(double x, double *integer_part)
{
  const uint64_t bits = double_to_bits(x);
  const uint64_t magnitude = bits & ~F64_SIGN_MASK;
  const unsigned encoded_exponent = (unsigned)(magnitude >> 52);

  if (encoded_exponent == 0x7ff)
  {
    if (f64_magnitude_is_signaling_nan(magnitude))
    {
      softfloat_raiseFlags(softfloat_flag_invalid);
      *integer_part = canonical_nan();
      return canonical_nan();
    }
    *integer_part = x;
    return f64_magnitude_is_nan(magnitude)
               ? x
               : double_from_bits(bits & F64_SIGN_MASK);
  }

  const int exponent = (int)encoded_exponent - 1023;
  if (exponent < 0)
  {
    *integer_part = double_from_bits(bits & F64_SIGN_MASK);
    return x;
  }
  if (exponent >= 52)
  {
    *integer_part = x;
    return double_from_bits(bits & F64_SIGN_MASK);
  }

  const uint64_t fraction_mask =
      (UINT64_C(1) << (unsigned)(52 - exponent)) - 1;
  if (!(bits & fraction_mask))
  {
    *integer_part = x;
    return double_from_bits(bits & F64_SIGN_MASK);
  }
  *integer_part = double_from_bits(bits & ~fraction_mask);
  return x - *integer_part;
}

float modff(float x, float *integer_part)
{
  const uint32_t bits = float_to_bits(x);
  const uint32_t magnitude = bits & ~F32_SIGN_MASK;
  const unsigned encoded_exponent = magnitude >> 23;

  if (encoded_exponent == 0xff)
  {
    if (f32_magnitude_is_signaling_nan(magnitude))
    {
      softfloat_raiseFlags(softfloat_flag_invalid);
      *integer_part = canonical_nanf();
      return canonical_nanf();
    }
    *integer_part = x;
    return f32_magnitude_is_nan(magnitude)
               ? x
               : float_from_bits(bits & F32_SIGN_MASK);
  }

  const int exponent = (int)encoded_exponent - 127;
  if (exponent < 0)
  {
    *integer_part = float_from_bits(bits & F32_SIGN_MASK);
    return x;
  }
  if (exponent >= 23)
  {
    *integer_part = x;
    return float_from_bits(bits & F32_SIGN_MASK);
  }

  const uint32_t fraction_mask =
      (UINT32_C(1) << (unsigned)(23 - exponent)) - 1;
  if (!(bits & fraction_mask))
  {
    *integer_part = x;
    return float_from_bits(bits & F32_SIGN_MASK);
  }
  *integer_part = float_from_bits(bits & ~fraction_mask);
  return x - *integer_part;
}

double nextafter(double x, double y)
{
  uint64_t x_bits = double_to_bits(x);
  const uint64_t y_bits = double_to_bits(y);
  const uint64_t x_magnitude = x_bits & ~F64_SIGN_MASK;
  const uint64_t y_magnitude = y_bits & ~F64_SIGN_MASK;

  if (f64_magnitude_is_nan(x_magnitude) ||
      f64_magnitude_is_nan(y_magnitude))
  {
    if (f64_magnitude_is_signaling_nan(x_magnitude) ||
        f64_magnitude_is_signaling_nan(y_magnitude))
    {
      softfloat_raiseFlags(softfloat_flag_invalid);
    }
    return canonical_nan();
  }
  if (x_bits == y_bits || (x_magnitude == 0 && y_magnitude == 0)) return y;
  if (x_magnitude == 0)
  {
    errno = ERANGE;
    softfloat_raiseFlags(softfloat_flag_underflow | softfloat_flag_inexact);
    return double_from_bits((y_bits & F64_SIGN_MASK) | UINT64_C(1));
  }

  if ((x < y) == !(x_bits & F64_SIGN_MASK))
    x_bits++;
  else
    x_bits--;

  const uint64_t result_magnitude = x_bits & ~F64_SIGN_MASK;
  if (result_magnitude == F64_EXP_MASK)
  {
    errno = ERANGE;
    softfloat_raiseFlags(softfloat_flag_overflow | softfloat_flag_inexact);
  }
  else if ((result_magnitude & F64_EXP_MASK) == 0)
  {
    errno = ERANGE;
    softfloat_raiseFlags(softfloat_flag_underflow | softfloat_flag_inexact);
  }
  return double_from_bits(x_bits);
}

float nextafterf(float x, float y)
{
  uint32_t x_bits = float_to_bits(x);
  const uint32_t y_bits = float_to_bits(y);
  const uint32_t x_magnitude = x_bits & ~F32_SIGN_MASK;
  const uint32_t y_magnitude = y_bits & ~F32_SIGN_MASK;

  if (f32_magnitude_is_nan(x_magnitude) ||
      f32_magnitude_is_nan(y_magnitude))
  {
    if (f32_magnitude_is_signaling_nan(x_magnitude) ||
        f32_magnitude_is_signaling_nan(y_magnitude))
    {
      softfloat_raiseFlags(softfloat_flag_invalid);
    }
    return canonical_nanf();
  }
  if (x_bits == y_bits || (x_magnitude == 0 && y_magnitude == 0)) return y;
  if (x_magnitude == 0)
  {
    errno = ERANGE;
    softfloat_raiseFlags(softfloat_flag_underflow | softfloat_flag_inexact);
    return float_from_bits((y_bits & F32_SIGN_MASK) | UINT32_C(1));
  }

  if ((x < y) == !(x_bits & F32_SIGN_MASK))
    x_bits++;
  else
    x_bits--;

  const uint32_t result_magnitude = x_bits & ~F32_SIGN_MASK;
  if (result_magnitude == F32_EXP_MASK)
  {
    errno = ERANGE;
    softfloat_raiseFlags(softfloat_flag_overflow | softfloat_flag_inexact);
  }
  else if ((result_magnitude & F32_EXP_MASK) == 0)
  {
    errno = ERANGE;
    softfloat_raiseFlags(softfloat_flag_underflow | softfloat_flag_inexact);
  }
  return float_from_bits(x_bits);
}

double floor(double x)
{
  return double_from_softfloat(
      f64_roundToInt(double_to_softfloat(x), softfloat_round_min, false));
}
double ceil(double x)
{
  return double_from_softfloat(
      f64_roundToInt(double_to_softfloat(x), softfloat_round_max, false));
}
double round(double x)
{
  return double_from_softfloat(f64_roundToInt(
      double_to_softfloat(x), softfloat_round_near_maxMag, false));
}
double trunc(double x)
{
  return double_from_softfloat(
      f64_roundToInt(double_to_softfloat(x), softfloat_round_minMag, false));
}
double fmod(double x, double y)
{
  const uint64_t x_bits = double_to_bits(x);
  const uint64_t y_bits = double_to_bits(y);
  const uint64_t sign = x_bits & F64_SIGN_MASK;
  const uint64_t x_magnitude = x_bits & ~F64_SIGN_MASK;
  const uint64_t y_magnitude = y_bits & ~F64_SIGN_MASK;

  /* NaN operands propagate a canonical NaN without creating a domain error. */
  if (f64_magnitude_is_nan(x_magnitude) ||
      f64_magnitude_is_nan(y_magnitude))
  {
    if (f64_magnitude_is_signaling_nan(x_magnitude) ||
        f64_magnitude_is_signaling_nan(y_magnitude))
    {
      softfloat_raiseFlags(softfloat_flag_invalid);
    }
    return canonical_nan();
  }

  /* fmod(infinity, y) and fmod(x, zero) are domain errors. */
  if (x_magnitude == F64_EXP_MASK || y_magnitude == 0)
  {
    errno = EDOM;
    softfloat_raiseFlags(softfloat_flag_invalid);
    return canonical_nan();
  }

  /* A finite dividend modulo infinity, and magnitudes below the divisor. */
  if (y_magnitude == F64_EXP_MASK || x_magnitude == 0 ||
      x_magnitude < y_magnitude)
  {
    return x;
  }
  if (x_magnitude == y_magnitude)
  {
    return double_from_bits(sign);
  }

  /*
   * Exact binary long division on the significands.  This is the same
   * shift/subtract method used by the fdlibm family: unlike IEEE remainder,
   * it removes powers-of-two multiples of |y| and therefore implements the
   * quotient-truncated-toward-zero definition required for fmod.
   *
   * Each value is represented as mantissa * 2^exponent with bit 52 of the
   * mantissa set.  The loop preserves the represented value while consuming
   * one quotient bit at a time.  No floating-point operation is involved.
   */
  uint64_t x_mantissa = x_magnitude & F64_FRAC_MASK;
  uint64_t y_mantissa = y_magnitude & F64_FRAC_MASK;
  int x_exponent = (int)(x_magnitude >> 52);
  int y_exponent = (int)(y_magnitude >> 52);

  if (x_exponent != 0)
  {
    x_mantissa |= F64_HIDDEN_BIT;
    x_exponent -= 1075;
  }
  else
  {
    x_exponent = -1074;
    while (!(x_mantissa & F64_HIDDEN_BIT))
    {
      x_mantissa <<= 1;
      x_exponent--;
    }
  }

  if (y_exponent != 0)
  {
    y_mantissa |= F64_HIDDEN_BIT;
    y_exponent -= 1075;
  }
  else
  {
    y_exponent = -1074;
    while (!(y_mantissa & F64_HIDDEN_BIT))
    {
      y_mantissa <<= 1;
      y_exponent--;
    }
  }

  while (x_exponent > y_exponent)
  {
    if (x_mantissa >= y_mantissa)
    {
      x_mantissa -= y_mantissa;
      if (x_mantissa == 0)
      {
        return double_from_bits(sign);
      }
    }
    x_mantissa <<= 1;
    x_exponent--;
  }
  if (x_mantissa >= y_mantissa)
  {
    x_mantissa -= y_mantissa;
  }
  if (x_mantissa == 0)
  {
    return double_from_bits(sign);
  }

  while (!(x_mantissa & F64_HIDDEN_BIT))
  {
    x_mantissa <<= 1;
    y_exponent--;
  }

  const int biased_exponent = y_exponent + 1075;
  uint64_t result_magnitude;
  if (biased_exponent > 0)
  {
    result_magnitude = ((uint64_t)biased_exponent << 52) |
                       (x_mantissa & F64_FRAC_MASK);
  }
  else
  {
    const int shift = 1 - biased_exponent;
    result_magnitude = shift < 64 ? x_mantissa >> shift : 0;
  }
  return double_from_bits(sign | result_magnitude);
}
double sqrt(double x)
{
  const uint64_t bits = double_to_bits(x);
  const uint64_t magnitude = bits & ~F64_SIGN_MASK;
  if ((bits & F64_SIGN_MASK) && magnitude != 0 &&
      !f64_magnitude_is_nan(magnitude))
  {
    errno = EDOM;
  }
  return double_from_softfloat(f64_sqrt(double_to_softfloat(x)));
}
double cbrt(double x)
{
  panic("cbrt: Not implemented");
}
double pow(double x, double y)
{
  panic("pow: Not implemented");
}
double exp(double x)
{
  panic("exp: Not implemented");
}
double exp2(double x)
{
  panic("exp2: Not implemented");
}
double log(double x)
{
  panic("log: Not implemented");
}
double log2(double x)
{
  panic("log2: Not implemented");
}
double log10(double x)
{
  panic("log10: Not implemented");
}
double sin(double x)
{
  panic("sin: Not implemented");
}
double cos(double x)
{
  panic("cos: Not implemented");
}
double tan(double x)
{
  panic("tan: Not implemented");
}
double asin(double x)
{
  panic("asin: Not implemented");
}
double acos(double x)
{
  panic("acos: Not implemented");
}
double atan(double x)
{
  panic("atan: Not implemented");
}
double atan2(double y, double x)
{
  panic("atan2: Not implemented");
}
#endif
