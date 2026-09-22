#include <klib.h>

#include <ctype.h>
#include <errno.h>
#include <limits.h>
#include <stdbool.h>
#include <stdint.h>

#include "stdio_impl.h"

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)

typedef enum
{
  SCAN_LENGTH_DEFAULT,
  SCAN_LENGTH_CHAR,
  SCAN_LENGTH_SHORT,
  SCAN_LENGTH_LONG,
  SCAN_LENGTH_LONG_LONG,
  SCAN_LENGTH_SIZE,
  SCAN_LENGTH_PTRDIFF,
  SCAN_LENGTH_INTMAX,
} ScanLength;

typedef struct
{
  uintmax_t magnitude;
  bool negative;
  bool any_digit;
  bool overflow;
} ScannedInteger;

typedef struct
{
  void *context;
  int (*read)(void *context);
  int (*unread)(int c, void *context);
  int pending;
  bool has_pending;
} ScanInput;

typedef struct
{
  const unsigned char *begin;
  const unsigned char *position;
} StringScanInput;

/* Keep va_arg pointer types exact even on an ABI where int and long have the
 * same width but size_t/ptrdiff_t choose only one of them. */
typedef __typeof__(_Generic((size_t)0,
    unsigned char: (signed char)0,
    unsigned short: (short)0,
    unsigned int: (int)0,
    unsigned long: (long)0,
    unsigned long long: (long long)0)) ScanSignedSize;

typedef __typeof__(_Generic((ptrdiff_t)0,
    signed char: (unsigned char)0,
    short: (unsigned short)0,
    int: (unsigned int)0,
    long: (unsigned long)0,
    long long: (unsigned long long)0)) ScanUnsignedPtrdiff;

static int scan_digit_value(unsigned char c)
{
  if (c >= '0' && c <= '9')
  {
    return c - '0';
  }
  if (c >= 'a' && c <= 'f')
  {
    return c - 'a' + 10;
  }
  if (c >= 'A' && c <= 'F')
  {
    return c - 'A' + 10;
  }
  return -1;
}

static int scan_string_read(void *context)
{
  StringScanInput *input = (StringScanInput *)context;
  if (*input->position == '\0')
  {
    return EOF;
  }
  return *input->position++;
}

static int scan_string_unread(int c, void *context)
{
  StringScanInput *input = (StringScanInput *)context;
  if (c == EOF || input->position <= input->begin)
  {
    return EOF;
  }
  input->position--;
  return (unsigned char)c;
}

static int scan_file_read(void *context)
{
  return fgetc((FILE *)context);
}

static int scan_file_unread(int c, void *context)
{
  return ungetc(c, (FILE *)context);
}

static int scan_input_read(ScanInput *input)
{
  if (input->has_pending)
  {
    input->has_pending = false;
    return input->pending;
  }
  return input->read(input->context);
}

static bool scan_input_unread(ScanInput *input, int c)
{
  if (c == EOF)
  {
    return true;
  }
  if (input->has_pending)
  {
    errno = EIO;
    return false;
  }
  input->pending = (unsigned char)c;
  input->has_pending = true;
  return true;
}

static bool scan_input_finish(ScanInput *input)
{
  if (!input->has_pending)
  {
    return true;
  }
  int c = input->pending;
  input->has_pending = false;
  if (input->unread(c, input->context) == EOF)
  {
    errno = EIO;
    return false;
  }
  return true;
}

static bool scan_skip_space(ScanInput *input)
{
  int c;
  do
  {
    c = scan_input_read(input);
  } while (c != EOF && isspace((unsigned char)c));
  if (c == EOF)
  {
    return false;
  }
  return scan_input_unread(input, c);
}

static int scan_limited_read(ScanInput *input, size_t *remaining)
{
  if (*remaining == 0)
  {
    return EOF;
  }
  int c = scan_input_read(input);
  if (c != EOF && *remaining != SIZE_MAX)
  {
    (*remaining)--;
  }
  return c;
}

static bool scan_limited_unread(
    ScanInput *input,
    int c,
    size_t *remaining)
{
  if (c == EOF)
  {
    return true;
  }
  if (!scan_input_unread(input, c))
  {
    return false;
  }
  if (*remaining != SIZE_MAX)
  {
    (*remaining)++;
  }
  return true;
}

static void scan_accumulate_digit(
    ScannedInteger *result,
    unsigned digit,
    unsigned base)
{
  const uintmax_t cutoff = UINTMAX_MAX / base;
  const unsigned cutlim = (unsigned)(UINTMAX_MAX % base);
  result->any_digit = true;
  if (result->overflow)
  {
    return;
  }
  if (result->magnitude > cutoff ||
      (result->magnitude == cutoff && digit > cutlim))
  {
    result->magnitude = UINTMAX_MAX;
    result->overflow = true;
    return;
  }
  result->magnitude = result->magnitude * base + digit;
}

static ScannedInteger scan_integer(
    ScanInput *input,
    size_t width,
    int base)
{
  ScannedInteger result = {
      .magnitude = 0,
      .negative = false,
      .any_digit = false,
      .overflow = false,
  };
  size_t remaining = width;

  int c = scan_limited_read(input, &remaining);
  if (c == '+' || c == '-')
  {
    result.negative = c == '-';
  }
  else
  {
    (void)scan_limited_unread(input, c, &remaining);
  }

  int actual_base = base;
  if (base == 0 || base == 16)
  {
    c = scan_limited_read(input, &remaining);
    if (c == '0')
    {
      int prefix = scan_limited_read(input, &remaining);
      if (prefix == 'x' || prefix == 'X')
      {
        actual_base = 16;
      }
      else
      {
        (void)scan_limited_unread(input, prefix, &remaining);
        actual_base = base == 0 ? 8 : 16;
        scan_accumulate_digit(&result, 0, (unsigned)actual_base);
      }
    }
    else
    {
      (void)scan_limited_unread(input, c, &remaining);
      actual_base = base == 0 ? 10 : 16;
    }
  }

  while (remaining != 0)
  {
    c = scan_limited_read(input, &remaining);
    if (c == EOF)
    {
      break;
    }
    int digit = scan_digit_value((unsigned char)c);
    if (digit < 0 || digit >= actual_base)
    {
      (void)scan_limited_unread(input, c, &remaining);
      break;
    }
    scan_accumulate_digit(&result, (unsigned)digit, (unsigned)actual_base);
  }
  return result;
}

static intmax_t scan_signed_value(
    const ScannedInteger *number,
    uintmax_t positive_limit,
    uintmax_t negative_limit)
{
  uintmax_t limit = number->negative ? negative_limit : positive_limit;
  if (number->overflow || number->magnitude > limit)
  {
    errno = ERANGE;
    return number->negative
               ? -(intmax_t)(negative_limit - 1) - 1
               : (intmax_t)positive_limit;
  }
  if (!number->negative)
  {
    return (intmax_t)number->magnitude;
  }
  if (number->magnitude == negative_limit)
  {
    return -(intmax_t)(negative_limit - 1) - 1;
  }
  return -(intmax_t)number->magnitude;
}

static uintmax_t scan_unsigned_value(
    const ScannedInteger *number,
    uintmax_t limit)
{
  if (number->overflow || number->magnitude > limit)
  {
    errno = ERANGE;
    return limit;
  }
  if (!number->negative || number->magnitude == 0)
  {
    return number->magnitude;
  }
  /* Apply the optional minus sign in the destination unsigned type. */
  return limit - number->magnitude + 1;
}

static void scan_store_signed(
    va_list *ap,
    ScanLength length,
    const ScannedInteger *number)
{
  switch (length)
  {
  case SCAN_LENGTH_CHAR:
    *va_arg(*ap, signed char *) = (signed char)scan_signed_value(
        number, SCHAR_MAX, (uintmax_t)SCHAR_MAX + 1);
    break;
  case SCAN_LENGTH_SHORT:
    *va_arg(*ap, short *) = (short)scan_signed_value(
        number, SHRT_MAX, (uintmax_t)SHRT_MAX + 1);
    break;
  case SCAN_LENGTH_DEFAULT:
    *va_arg(*ap, int *) = (int)scan_signed_value(
        number, INT_MAX, (uintmax_t)INT_MAX + 1);
    break;
  case SCAN_LENGTH_LONG:
    *va_arg(*ap, long *) = (long)scan_signed_value(
        number, LONG_MAX, (uintmax_t)LONG_MAX + 1);
    break;
  case SCAN_LENGTH_LONG_LONG:
    *va_arg(*ap, long long *) = (long long)scan_signed_value(
        number, LLONG_MAX, (uintmax_t)LLONG_MAX + 1);
    break;
  case SCAN_LENGTH_SIZE:
    *va_arg(*ap, ScanSignedSize *) = (ScanSignedSize)scan_signed_value(
        number, SIZE_MAX >> 1, (SIZE_MAX >> 1) + 1);
    break;
  case SCAN_LENGTH_PTRDIFF:
    *va_arg(*ap, ptrdiff_t *) = (ptrdiff_t)scan_signed_value(
        number, PTRDIFF_MAX, (uintmax_t)PTRDIFF_MAX + 1);
    break;
  case SCAN_LENGTH_INTMAX:
    *va_arg(*ap, intmax_t *) = scan_signed_value(
        number, INTMAX_MAX, (uintmax_t)INTMAX_MAX + 1);
    break;
  }
}

static void scan_store_unsigned(
    va_list *ap,
    ScanLength length,
    const ScannedInteger *number)
{
  switch (length)
  {
  case SCAN_LENGTH_CHAR:
    *va_arg(*ap, unsigned char *) = (unsigned char)scan_unsigned_value(
        number, UCHAR_MAX);
    break;
  case SCAN_LENGTH_SHORT:
    *va_arg(*ap, unsigned short *) = (unsigned short)scan_unsigned_value(
        number, USHRT_MAX);
    break;
  case SCAN_LENGTH_DEFAULT:
    *va_arg(*ap, unsigned int *) = (unsigned int)scan_unsigned_value(
        number, UINT_MAX);
    break;
  case SCAN_LENGTH_LONG:
    *va_arg(*ap, unsigned long *) = (unsigned long)scan_unsigned_value(
        number, ULONG_MAX);
    break;
  case SCAN_LENGTH_LONG_LONG:
    *va_arg(*ap, unsigned long long *) =
        (unsigned long long)scan_unsigned_value(number, ULLONG_MAX);
    break;
  case SCAN_LENGTH_SIZE:
    *va_arg(*ap, size_t *) = (size_t)scan_unsigned_value(number, SIZE_MAX);
    break;
  case SCAN_LENGTH_PTRDIFF:
    *va_arg(*ap, ScanUnsignedPtrdiff *) =
        (ScanUnsignedPtrdiff)scan_unsigned_value(
            number, (uintmax_t)PTRDIFF_MAX * 2 + 1);
    break;
  case SCAN_LENGTH_INTMAX:
    *va_arg(*ap, uintmax_t *) = scan_unsigned_value(number, UINTMAX_MAX);
    break;
  }
}

static bool scan_parse_width(const char **format, size_t *width, bool *specified)
{
  const char *p = *format;
  *width = 0;
  *specified = false;
  while (*p >= '0' && *p <= '9')
  {
    *specified = true;
    unsigned digit = (unsigned)(*p - '0');
    if (*width > (SIZE_MAX - digit) / 10)
    {
      *width = SIZE_MAX;
    }
    else
    {
      *width = *width * 10 + digit;
    }
    p++;
  }
  *format = p;
  return !*specified || *width != 0;
}

static bool scan_parse_length(const char **format, ScanLength *length)
{
  const char *p = *format;
  *length = SCAN_LENGTH_DEFAULT;
  if (*p == 'h')
  {
    p++;
    if (*p == 'h')
    {
      p++;
      *length = SCAN_LENGTH_CHAR;
    }
    else
    {
      *length = SCAN_LENGTH_SHORT;
    }
  }
  else if (*p == 'l')
  {
    p++;
    if (*p == 'l')
    {
      p++;
      *length = SCAN_LENGTH_LONG_LONG;
    }
    else
    {
      *length = SCAN_LENGTH_LONG;
    }
  }
  else if (*p == 'z')
  {
    p++;
    *length = SCAN_LENGTH_SIZE;
  }
  else if (*p == 't')
  {
    p++;
    *length = SCAN_LENGTH_PTRDIFF;
  }
  else if (*p == 'j')
  {
    p++;
    *length = SCAN_LENGTH_INTMAX;
  }
  *format = p;
  return true;
}

static int scan_input_failure(int assignments)
{
  return assignments == 0 ? EOF : assignments;
}

static bool scan_assignment_available(int assignments)
{
  if (assignments == INT_MAX)
  {
    errno = EOVERFLOW;
    return false;
  }
  return true;
}

static int scan_core(
    ScanInput *input,
    const char *__restrict format,
    va_list ap)
{
  const char *fmt = format;
  int assignments = 0;
  va_list args;
  va_copy(args, ap);

  while (*fmt != '\0')
  {
    if (isspace((unsigned char)*fmt))
    {
      do
      {
        fmt++;
      } while (isspace((unsigned char)*fmt));
      (void)scan_skip_space(input);
      continue;
    }

    if (*fmt != '%')
    {
      int c = scan_input_read(input);
      if (c == EOF)
      {
        va_end(args);
        return scan_input_failure(assignments);
      }
      if (c != (unsigned char)*fmt)
      {
        (void)scan_input_unread(input, c);
        va_end(args);
        return assignments;
      }
      fmt++;
      continue;
    }

    fmt++;
    bool suppress = false;
    if (*fmt == '*')
    {
      suppress = true;
      fmt++;
    }

    size_t width;
    bool width_specified;
    if (!scan_parse_width(&fmt, &width, &width_specified))
    {
      errno = EINVAL;
      va_end(args);
      return assignments;
    }

    ScanLength length;
    scan_parse_length(&fmt, &length);
    char conversion = *fmt;
    if (conversion == '\0')
    {
      errno = EINVAL;
      va_end(args);
      return assignments;
    }
    fmt++;

    if (conversion == '%')
    {
      if (suppress || width_specified || length != SCAN_LENGTH_DEFAULT)
      {
        errno = EINVAL;
        va_end(args);
        return assignments;
      }
      int c = scan_input_read(input);
      if (c == EOF)
      {
        va_end(args);
        return scan_input_failure(assignments);
      }
      if (c != '%')
      {
        (void)scan_input_unread(input, c);
        va_end(args);
        return assignments;
      }
      continue;
    }

    bool integer_conversion =
        conversion == 'd' || conversion == 'i' || conversion == 'u' ||
        conversion == 'x' || conversion == 'X' || conversion == 'o' ||
        conversion == 'p';
    if (integer_conversion)
    {
      if (conversion == 'p' && length != SCAN_LENGTH_DEFAULT)
      {
        errno = EINVAL;
        va_end(args);
        return assignments;
      }
      if (!scan_skip_space(input))
      {
        va_end(args);
        return scan_input_failure(assignments);
      }

      int base = 10;
      if (conversion == 'i')
      {
        base = 0;
      }
      else if (conversion == 'x' || conversion == 'X' || conversion == 'p')
      {
        base = 16;
      }
      else if (conversion == 'o')
      {
        base = 8;
      }
      ScannedInteger number = scan_integer(
          input, width_specified ? width : SIZE_MAX, base);
      if (!number.any_digit)
      {
        va_end(args);
        return assignments;
      }
      if (!suppress)
      {
        if (!scan_assignment_available(assignments))
        {
          va_end(args);
          return assignments;
        }
        if (conversion == 'd' || conversion == 'i')
        {
          scan_store_signed(&args, length, &number);
        }
        else if (conversion == 'p')
        {
          uintmax_t value = scan_unsigned_value(&number, UINTPTR_MAX);
          *va_arg(args, void **) = (void *)(uintptr_t)value;
        }
        else
        {
          scan_store_unsigned(&args, length, &number);
        }
        assignments++;
      }
      continue;
    }

    if (conversion == 'c')
    {
      if (length != SCAN_LENGTH_DEFAULT)
      {
        errno = EINVAL;
        va_end(args);
        return assignments;
      }
      size_t count = width_specified ? width : 1;
      if (!suppress && !scan_assignment_available(assignments))
      {
        va_end(args);
        return assignments;
      }
      char *destination = suppress ? NULL : va_arg(args, char *);
      for (size_t i = 0; i < count; i++)
      {
        int c = scan_input_read(input);
        if (c == EOF)
        {
          va_end(args);
          return scan_input_failure(assignments);
        }
        if (!suppress)
        {
          destination[i] = (char)(unsigned char)c;
        }
      }
      if (!suppress)
      {
        assignments++;
      }
      continue;
    }

    if (conversion == 's')
    {
      if (length != SCAN_LENGTH_DEFAULT)
      {
        errno = EINVAL;
        va_end(args);
        return assignments;
      }
      if (!scan_skip_space(input))
      {
        va_end(args);
        return scan_input_failure(assignments);
      }
      size_t count = 0;
      size_t limit = width_specified ? width : SIZE_MAX;
      if (!suppress && !scan_assignment_available(assignments))
      {
        va_end(args);
        return assignments;
      }
      char *destination = suppress ? NULL : va_arg(args, char *);
      while (count < limit)
      {
        int c = scan_input_read(input);
        if (c == EOF)
        {
          break;
        }
        if (isspace((unsigned char)c))
        {
          (void)scan_input_unread(input, c);
          break;
        }
        if (!suppress)
        {
          destination[count] = (char)(unsigned char)c;
        }
        count++;
      }
      if (count == 0)
      {
        va_end(args);
        return assignments;
      }
      if (!suppress)
      {
        destination[count] = '\0';
        assignments++;
      }
      continue;
    }

    /* Unsupported conversions must not consume a variadic argument. */
    errno = EINVAL;
    va_end(args);
    return assignments;
  }

  va_end(args);
  return assignments;
}

int vsscanf(const char *__restrict str,
            const char *__restrict format,
            va_list ap)
{
  StringScanInput source = {
      .begin = (const unsigned char *)str,
      .position = (const unsigned char *)str,
  };
  ScanInput input = {
      .context = &source,
      .read = scan_string_read,
      .unread = scan_string_unread,
  };
  int result = scan_core(&input, format, ap);
  (void)scan_input_finish(&input);
  return result;
}

int sscanf(const char *__restrict str,
           const char *__restrict format,
           ...)
{
  va_list ap;
  va_start(ap, format);
  int result = vsscanf(str, format, ap);
  va_end(ap);
  return result;
}

int vfscanf(FILE *__restrict stream,
            const char *__restrict format,
            va_list ap)
{
  if (stream == NULL || format == NULL)
  {
    errno = EINVAL;
    return EOF;
  }
  ScanInput input = {
      .context = stream,
      .read = scan_file_read,
      .unread = scan_file_unread,
  };
  int result = scan_core(&input, format, ap);
  (void)scan_input_finish(&input);
  return result;
}

int fscanf(FILE *__restrict stream,
           const char *__restrict format,
           ...)
{
  va_list ap;
  va_start(ap, format);
  int result = vfscanf(stream, format, ap);
  va_end(ap);
  return result;
}

int vscanf(const char *__restrict format, va_list ap)
{
  return vfscanf(stdin, format, ap);
}

int scanf(const char *__restrict format, ...)
{
  va_list ap;
  va_start(ap, format);
  int result = vscanf(format, ap);
  va_end(ap);
  return result;
}

#endif
