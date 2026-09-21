#include <am.h>
#include <klib.h>
#include <klib-macros.h>
#include <stdarg.h>

#include <errno.h>
#include <limits.h>

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)

// 自己写的
#ifndef EOF
#define EOF (-1)
#endif
struct cookie
{
  char *s;  // 指向缓冲区中下一个可写入的位置
  size_t n; // 缓冲区剩余可用字节数，不包括结尾的\0
};
typedef struct FILE FILE;
struct FILE
{
  size_t (*write)(FILE *f, const unsigned char *buf, size_t len);
  size_t (*repeat)(FILE *f, unsigned char ch, size_t len);
  void *cookie;
  size_t count; // 这玩意是逻辑上“本来想写”的总长度
  int err;
  unsigned char *buf;   // 内部写缓冲起点
  unsigned char *wbase; // 当前待刷出的起点
  unsigned char *wpos;  // 当前写到哪里了
  unsigned char *wend;
  int lbf;
  int lock;
};
enum
{
  FILE_ERROR_NONE,
  FILE_ERROR_WRITE,
  FILE_ERROR_FORMAT,
  FILE_ERROR_OVERFLOW
};
static size_t console_write(FILE *f, const unsigned char *s, size_t l); // 这是底层输出的
static size_t console_repeat(FILE *f, unsigned char ch, size_t len);
static FILE __stdout_FILE = {
    .lbf = EOF,
    .lock = -1,
    .write = console_write,
    .repeat = console_repeat,
    .cookie = NULL,
    .buf = NULL,
    .wbase = NULL,
    .wpos = NULL,
    .wend = NULL,
    .count = 0,
    .err = 0,
};
FILE *stdout = &__stdout_FILE;
KFILE *kstdout = (KFILE *)&__stdout_FILE;
#define MIN(a, b) ((a) < (b) ? (a) : (b))

// 自己写的
static size_t sn_write(FILE *f, const unsigned char *s, size_t l);
static size_t sn_repeat(FILE *f, unsigned char ch, size_t len);
/*
static void byte_copy(char *dst, const unsigned char *src, size_t n);
static size_t cstr_len(const char *s);
*/
int vfprintf(FILE *f, const char *fmt, va_list ap);
// vfprintf和write之间的中间层，就反正是vfprintf不直接碰sn_write，然后具体怎么写就直接让f->write决定
static int file_write(FILE *f, const void *buf, size_t len);
static int file_putc(FILE *f, char ch);       // file_write的1字节包装
static int file_pad(FILE *f, char ch, size_t n); // 连续输出多个填充字符
static int file_check_count(FILE *f, size_t len);
static int file_set_error(FILE *f, int error, int error_number);
// 负责把一个无符号整数转成字符串，但先倒着存。
static int uint_to_rev(uintmax_t x, unsigned base, int uppercase, char *buf);
typedef enum
{
  PRINTF_LENGTH_DEFAULT,
  PRINTF_LENGTH_CHAR,
  PRINTF_LENGTH_SHORT,
  PRINTF_LENGTH_LONG,
  PRINTF_LENGTH_LONG_LONG,
  PRINTF_LENGTH_SIZE,
  PRINTF_LENGTH_PTRDIFF,
  PRINTF_LENGTH_INTMAX
} PrintfLength;
typedef struct
{
  int width;
  int precision; // -1表示没有指定精度
  int left_align;
  int show_plus;
  int space_sign;
  int alternate;
  int zero_pad;
  PrintfLength length;
} PrintfSpec;
_Static_assert(
    sizeof(ptrdiff_t) == sizeof(size_t),
    "%zd需要ptrdiff_t与size_t使用相同宽度");
static int print_number(FILE *f, uintmax_t x, unsigned base, int uppercase, const PrintfSpec *spec, char sign, int pointer_prefix);
static int parse_decimal(const char **ps, int *value);
static int parse_spec(FILE *f, const char **pfmt, va_list *ap, PrintfSpec *spec);
static int printf_core(FILE *f, const char *fmt, va_list *ap);
static int print_str(FILE *f, const char *s, const PrintfSpec *spec);
static int print_char(FILE *f, char ch, const PrintfSpec *spec);
static int print_uint(FILE *f, uintmax_t x, unsigned base, int uppercase, const PrintfSpec *spec);
static int print_int(FILE *f, intmax_t x, const PrintfSpec *spec);
static intmax_t read_signed_arg(va_list *ap, PrintfLength length);
static uintmax_t read_unsigned_arg(va_list *ap, PrintfLength length);
static int format_error(FILE *f);
static size_t console_write(FILE *f, const unsigned char *s, size_t l);
int fprintf(FILE *stream, const char *fmt, ...);

int printf(const char *fmt, ...)
{
  // panic("Not implemented");
  int ret;
  va_list ap;
  va_start(ap, fmt);
  ret = vfprintf(stdout, fmt, ap);
  va_end(ap);
  return ret;
}

int vsprintf(char *out, const char *fmt, va_list ap)
{
  // panic("Not implemented");
  return vsnprintf(out, (size_t)INT_MAX + 1u, fmt, ap);
}

int sprintf(char *out, const char *fmt, ...)
{
  // panic("Not implemented");
  int ret;
  va_list ap;
  va_start(ap, fmt);
  ret = vsprintf(out, fmt, ap);
  va_end(ap);
  return ret;
}

int snprintf(char *out, size_t n, const char *fmt, ...)
{
  // panic("Not implemented");
  int ret;
  va_list ap;
  va_start(ap, fmt);
  ret = vsnprintf(out, n, fmt, ap);
  va_end(ap);
  return ret;
}

int vsnprintf(char *out, size_t n, const char *fmt, va_list ap)
{
  // panic("Not implemented");
  unsigned char buf[1];
  char dummy[1];
  struct cookie c = {.s = n ? out : dummy, .n = n ? n - 1 : 0};
  FILE f = {
      .lbf = EOF,
      .write = sn_write,
      .repeat = sn_repeat,
      .lock = -1,
      .buf = buf,
      .wbase = buf,
      .wpos = buf,
      .cookie = &c,
      .count = 0,
      .err = 0,
  };
  *c.s = '\0';
  return vfprintf(&f, fmt, ap);
}
static size_t sn_write(FILE *f, const unsigned char *s, size_t l)
{
  struct cookie *c = (struct cookie *)f->cookie;
  // 先把FILE内部缓冲里还没倒出去的旧数据拷到用户字符串
  size_t k = MIN(c->n, (size_t)(f->wpos - f->wbase));
  if (k)
  {
    memcpy(c->s, f->wbase, k);
    c->s += k;
    c->n -= k;
  }
  // 再拷这次新来的数据
  k = MIN(c->n, l);
  if (k)
  {
    memcpy(c->s, s, k);
    c->s += k;
    c->n -= k;
  }
  *c->s = 0; // snprintf只要n>0，就始终保持字符串可终止
  f->wpos = f->wbase = f->buf;
  /* pretend to succeed, even if we discarded extra data */
  // 翻译：即使我们丢弃了额外数据，也要假装成功。
  return l;
}
static size_t sn_repeat(FILE *f, unsigned char ch, size_t len)
{
  struct cookie *c = (struct cookie *)f->cookie;
  size_t copied = MIN(c->n, len);
  if (copied != 0)
  {
    memset(c->s, ch, copied);
    c->s += copied;
    c->n -= copied;
  }
  *c->s = '\0';
  f->wpos = f->wbase = f->buf;
  return len;
}
int vfprintf(FILE *f, const char *fmt, va_list ap)
{
  va_list ap2;
  int ret;
  va_copy(ap2, ap);
  f->count = 0;
  f->err = 0;
  ret = printf_core(f, fmt, &ap2);
  va_end(ap2);
  return f->err ? -1 : ret;
}
static int file_set_error(FILE *f, int error, int error_number)
{
  if (f->err == FILE_ERROR_NONE)
  {
    f->err = error;
    if (error_number != 0)
    {
      errno = error_number;
    }
  }
  return -1;
}
static int file_check_count(FILE *f, size_t len)
{
  if (f->err != FILE_ERROR_NONE)
  {
    return -1;
  }
  if (f->count > (size_t)INT_MAX ||
      len > (size_t)INT_MAX - f->count)
  {
    return file_set_error(f, FILE_ERROR_OVERFLOW, EOVERFLOW);
  }
  return 0;
}
static int file_write(FILE *f, const void *buf, size_t len)
{
  if (len == 0)
  {
    return 0;
  }
  if (file_check_count(f, len) < 0)
  {
    return -1;
  }
  if (f->write(f, (const unsigned char *)buf, len) != len)
  {
    return file_set_error(f, FILE_ERROR_WRITE, 0);
  }
  f->count += len;
  return 0;
}
static int file_putc(FILE *f, char ch)
{
  return file_write(f, &ch, 1);
}
static int file_pad(FILE *f, char ch, size_t n)
{
  if (n == 0)
  {
    return 0;
  }
  if (file_check_count(f, n) < 0)
  {
    return -1;
  }
  if (f->repeat(f, (unsigned char)ch, n) != n)
  {
    return file_set_error(f, FILE_ERROR_WRITE, 0);
  }
  f->count += n;
  return 0;
}
static int uint_to_rev(uintmax_t x, unsigned base, int uppercase, char *buf)
{
  static const char lower_digits[] = "0123456789abcdef";
  static const char upper_digits[] = "0123456789ABCDEF";
  const char *digits = uppercase ? upper_digits : lower_digits;
  int n = 0;
  do
  {
    buf[n++] = digits[x % base];
    x /= base;
  } while (x != 0);
  return n;
}
static int print_number(FILE *f, uintmax_t x, unsigned base, int uppercase, const PrintfSpec *spec, char sign, int pointer_prefix)
{
  char tmp[sizeof(uintmax_t) * CHAR_BIT];
  int ndig = (spec->precision == 0 && x == 0 && !pointer_prefix)
                 ? 0
                 : uint_to_rev(x, base, uppercase, tmp);
  const char *prefix = NULL;
  int prefix_len = 0;
  if (pointer_prefix)
  {
    prefix = "0x";
    prefix_len = 2;
  }
  else if (spec->alternate && base == 16 && x != 0)
  {
    prefix = uppercase ? "0X" : "0x";
    prefix_len = 2;
  }

  int precision_zeroes = 0;
  if (spec->precision > ndig)
  {
    precision_zeroes = spec->precision - ndig;
  }
  if (spec->alternate && base == 8 && precision_zeroes == 0 &&
      (ndig == 0 || tmp[ndig - 1] != '0'))
  {
    precision_zeroes = 1;
  }

  size_t total = (sign ? 1u : 0u) + (size_t)prefix_len +
                 (size_t)precision_zeroes + (size_t)ndig;
  size_t field_length = total < (size_t)spec->width
                            ? (size_t)spec->width
                            : total;
  if (file_check_count(f, field_length) < 0)
  {
    return -1;
  }
  int padding = (total < (size_t)spec->width)
                    ? spec->width - (int)total
                    : 0;
  int width_zeroes = 0;
  if (!spec->left_align && spec->zero_pad && spec->precision < 0)
  {
    width_zeroes = padding;
    padding = 0;
  }

  if (!spec->left_align && file_pad(f, ' ', (size_t)padding) < 0)
  {
    return -1;
  }
  if (sign && file_putc(f, sign) < 0)
  {
    return -1;
  }
  if (prefix_len && file_write(f, prefix, (size_t)prefix_len) < 0)
  {
    return -1;
  }
  if (file_pad(f, '0', (size_t)width_zeroes) < 0 ||
      file_pad(f, '0', (size_t)precision_zeroes) < 0)
  {
    return -1;
  }
  while (ndig > 0)
  {
    if (file_putc(f, tmp[--ndig]) < 0)
    {
      return -1;
    }
  }
  if (spec->left_align && file_pad(f, ' ', (size_t)padding) < 0)
  {
    return -1;
  }
  return 0;
}
static int parse_decimal(const char **ps, int *value)
{
  int result = 0;
  while (**ps >= '0' && **ps <= '9')
  {
    int digit = **ps - '0';
    if (result > (INT_MAX - digit) / 10)
    {
      return -1;
    }
    result = result * 10 + digit;
    (*ps)++;
  }
  *value = result;
  return 0;
}
static int parse_spec(FILE *f, const char **pfmt, va_list *ap, PrintfSpec *spec)
{
  const char *fmt = *pfmt;
  *spec = (PrintfSpec){
      .width = 0,
      .precision = -1,
      .length = PRINTF_LENGTH_DEFAULT,
  };

  int parsing_flags = 1;
  while (parsing_flags)
  {
    switch (*fmt)
    {
    case '-': spec->left_align = 1; fmt++; break;
    case '+': spec->show_plus = 1; fmt++; break;
    case ' ': spec->space_sign = 1; fmt++; break;
    case '#': spec->alternate = 1; fmt++; break;
    case '0': spec->zero_pad = 1; fmt++; break;
    default: parsing_flags = 0; break;
    }
  }

  if (*fmt == '*')
  {
    int dynamic_width = va_arg(*ap, int);
    fmt++;
    if (dynamic_width == INT_MIN)
    {
      return file_set_error(f, FILE_ERROR_OVERFLOW, EOVERFLOW);
    }
    if (dynamic_width < 0)
    {
      spec->left_align = 1;
      dynamic_width = -dynamic_width;
    }
    spec->width = dynamic_width;
  }
  else if (*fmt >= '0' && *fmt <= '9')
  {
    if (parse_decimal(&fmt, &spec->width) < 0)
    {
      return file_set_error(f, FILE_ERROR_OVERFLOW, EOVERFLOW);
    }
  }

  if (*fmt == '.')
  {
    fmt++;
    spec->precision = 0;
    if (*fmt == '*')
    {
      int dynamic_precision = va_arg(*ap, int);
      fmt++;
      spec->precision = dynamic_precision < 0 ? -1 : dynamic_precision;
    }
    else if (*fmt >= '0' && *fmt <= '9')
    {
      if (parse_decimal(&fmt, &spec->precision) < 0)
      {
        return file_set_error(f, FILE_ERROR_OVERFLOW, EOVERFLOW);
      }
    }
  }

  if (*fmt == 'h')
  {
    spec->length = PRINTF_LENGTH_SHORT;
    fmt++;
    if (*fmt == 'h')
    {
      spec->length = PRINTF_LENGTH_CHAR;
      fmt++;
    }
  }
  else if (*fmt == 'l')
  {
    spec->length = PRINTF_LENGTH_LONG;
    fmt++;
    if (*fmt == 'l')
    {
      spec->length = PRINTF_LENGTH_LONG_LONG;
      fmt++;
    }
  }
  else if (*fmt == 'z')
  {
    spec->length = PRINTF_LENGTH_SIZE;
    fmt++;
  }
  else if (*fmt == 't')
  {
    spec->length = PRINTF_LENGTH_PTRDIFF;
    fmt++;
  }
  else if (*fmt == 'j')
  {
    spec->length = PRINTF_LENGTH_INTMAX;
    fmt++;
  }

  if (spec->left_align)
  {
    spec->zero_pad = 0;
  }
  if (spec->show_plus)
  {
    spec->space_sign = 0;
  }
  *pfmt = fmt;
  return 0;
}
static int print_str(FILE *f, const char *s, const PrintfSpec *spec)
{
  if (s == NULL)
  {
    s = "(null)";
  }
  size_t len = 0;
  while ((spec->precision < 0 || len < (size_t)spec->precision) && s[len])
  {
    len++;
  }
  int padding = (len < (size_t)spec->width)
                    ? spec->width - (int)len
                    : 0;
  size_t field_length = len < (size_t)spec->width
                            ? (size_t)spec->width
                            : len;
  if (file_check_count(f, field_length) < 0)
  {
    return -1;
  }
  if (!spec->left_align && file_pad(f, ' ', (size_t)padding) < 0)
  {
    return -1;
  }
  if (file_write(f, s, len) < 0)
  {
    return -1;
  }
  if (spec->left_align && file_pad(f, ' ', (size_t)padding) < 0)
  {
    return -1;
  }
  return 0;
}
static int print_char(FILE *f, char ch, const PrintfSpec *spec)
{
  int padding = spec->width > 1 ? spec->width - 1 : 0;
  size_t field_length = spec->width > 1 ? (size_t)spec->width : 1u;
  if (file_check_count(f, field_length) < 0)
  {
    return -1;
  }
  if (!spec->left_align && file_pad(f, ' ', (size_t)padding) < 0)
  {
    return -1;
  }
  if (file_putc(f, ch) < 0)
  {
    return -1;
  }
  if (spec->left_align && file_pad(f, ' ', (size_t)padding) < 0)
  {
    return -1;
  }
  return 0;
}
static int print_uint(FILE *f, uintmax_t x, unsigned base, int uppercase, const PrintfSpec *spec)
{
  return print_number(f, x, base, uppercase, spec, '\0', 0);
}
static int print_int(FILE *f, intmax_t x, const PrintfSpec *spec)
{
  char sign = '\0';
  uintmax_t magnitude;
  if (x < 0)
  {
    sign = '-';
    magnitude = (uintmax_t)0 - (uintmax_t)x;
  }
  else
  {
    magnitude = (uintmax_t)x;
    if (spec->show_plus)
    {
      sign = '+';
    }
    else if (spec->space_sign)
    {
      sign = ' ';
    }
  }
  return print_number(f, magnitude, 10, 0, spec, sign, 0);
}
static intmax_t read_signed_arg(va_list *ap, PrintfLength length)
{
  switch (length)
  {
  case PRINTF_LENGTH_CHAR:
    return (signed char)va_arg(*ap, int);
  case PRINTF_LENGTH_SHORT:
    return (short)va_arg(*ap, int);
  case PRINTF_LENGTH_LONG:
    return va_arg(*ap, long);
  case PRINTF_LENGTH_LONG_LONG:
    return va_arg(*ap, long long);
  case PRINTF_LENGTH_SIZE:
  case PRINTF_LENGTH_PTRDIFF:
    return va_arg(*ap, ptrdiff_t);
  case PRINTF_LENGTH_INTMAX:
    return va_arg(*ap, intmax_t);
  case PRINTF_LENGTH_DEFAULT:
  default:
    return va_arg(*ap, int);
  }
}
static uintmax_t read_unsigned_arg(va_list *ap, PrintfLength length)
{
  switch (length)
  {
  case PRINTF_LENGTH_CHAR:
#if UCHAR_MAX <= INT_MAX
    return (unsigned char)va_arg(*ap, int);
#else
    return (unsigned char)va_arg(*ap, unsigned int);
#endif
  case PRINTF_LENGTH_SHORT:
#if USHRT_MAX <= INT_MAX
    return (unsigned short)va_arg(*ap, int);
#else
    return (unsigned short)va_arg(*ap, unsigned int);
#endif
  case PRINTF_LENGTH_LONG:
    return va_arg(*ap, unsigned long);
  case PRINTF_LENGTH_LONG_LONG:
    return va_arg(*ap, unsigned long long);
  case PRINTF_LENGTH_SIZE:
  case PRINTF_LENGTH_PTRDIFF:
    return va_arg(*ap, size_t);
  case PRINTF_LENGTH_INTMAX:
    return va_arg(*ap, uintmax_t);
  case PRINTF_LENGTH_DEFAULT:
  default:
    return va_arg(*ap, unsigned int);
  }
}
static int format_error(FILE *f)
{
  return file_set_error(f, FILE_ERROR_FORMAT, EINVAL);
}
static int printf_core(FILE *f, const char *fmt, va_list *ap)
{
  while (*fmt)
  {
    if (*fmt != '%')
    {
      if (file_putc(f, *fmt) < 0)
      {
        return -1;
      }
      fmt++;
      continue;
    }

    fmt++;
    PrintfSpec spec;
    if (parse_spec(f, &fmt, ap, &spec) < 0)
    {
      return -1;
    }

    switch (*fmt)
    {
    case '%':
      if (spec.length != PRINTF_LENGTH_DEFAULT || spec.precision >= 0 ||
          spec.show_plus || spec.space_sign || spec.alternate)
      {
        return format_error(f);
      }
      if (print_char(f, '%', &spec) < 0)
      {
        return -1;
      }
      break;
    case 'c':
      if (spec.length != PRINTF_LENGTH_DEFAULT || spec.precision >= 0 ||
          spec.show_plus || spec.space_sign || spec.alternate)
      {
        return format_error(f);
      }
      if (print_char(f, (char)va_arg(*ap, int), &spec) < 0)
      {
        return -1;
      }
      break;
    case 's':
      if (spec.length != PRINTF_LENGTH_DEFAULT || spec.show_plus ||
          spec.space_sign || spec.alternate)
      {
        return format_error(f);
      }
      if (print_str(f, va_arg(*ap, const char *), &spec) < 0)
      {
        return -1;
      }
      break;
    case 'd':
    case 'i':
    {
      if (spec.alternate)
      {
        return format_error(f);
      }
      intmax_t val = read_signed_arg(ap, spec.length);
      if (print_int(f, val, &spec) < 0)
      {
        return -1;
      }
      break;
    }
    case 'u':
    {
      if (spec.show_plus || spec.space_sign || spec.alternate)
      {
        return format_error(f);
      }
      uintmax_t val = read_unsigned_arg(ap, spec.length);
      if (print_uint(f, val, 10, 0, &spec) < 0)
      {
        return -1;
      }
      break;
    }

    case 'x':
    case 'X':
    {
      if (spec.show_plus || spec.space_sign)
      {
        return format_error(f);
      }
      uintmax_t val = read_unsigned_arg(ap, spec.length);
      if (print_uint(f, val, 16, *fmt == 'X', &spec) < 0)
      {
        return -1;
      }
      break;
    }
    case 'o':
    {
      if (spec.show_plus || spec.space_sign)
      {
        return format_error(f);
      }
      uintmax_t val = read_unsigned_arg(ap, spec.length);
      if (print_uint(f, val, 8, 0, &spec) < 0)
      {
        return -1;
      }
      break;
    }
    case 'p':
    {
      if (spec.length != PRINTF_LENGTH_DEFAULT || spec.show_plus ||
          spec.space_sign || spec.alternate)
      {
        return format_error(f);
      }
      uintmax_t value = (uintmax_t)(uintptr_t)va_arg(*ap, void *);
      if (print_number(f, value, 16, 0, &spec, '\0', 1) < 0)
      {
        return -1;
      }
      break;
    }
    case '\0':
      return format_error(f);
    default:
      // 不知道参数类型时不能猜测va_arg；立即停止，避免后续格式读取错位。
      return format_error(f);
    }
    fmt++;
  }
  if (file_check_count(f, 0) < 0)
  {
    return -1;
  }
  return (int)f->count;
}
static size_t console_write(FILE *f, const unsigned char *s, size_t l)
{
  (void)f;
  for (size_t i = 0; i < l; i++)
  {
    putch((char)s[i]);
  }
  return l;
}
static size_t console_repeat(FILE *f, unsigned char ch, size_t len)
{
  (void)f;
  for (size_t i = 0; i < len; i++)
  {
    putch((char)ch);
  }
  return len;
}
int fprintf(FILE *stream, const char *fmt, ...)
{
  int ret;
  va_list ap;
  va_start(ap, fmt);
  ret = vfprintf(stream, fmt, ap);
  va_end(ap);
  return ret;
}
// 他妈的烦死这个兼容选项了
int kvfprintf(KFILE *stream, const char *fmt, va_list ap)
{
  return vfprintf((FILE *)stream, fmt, ap);
}
int kfprintf(KFILE *stream, const char *fmt, ...)
{
  va_list ap;
  va_start(ap, fmt);
  int ret = vfprintf((FILE *)stream, fmt, ap);
  va_end(ap);
  return ret;
}
#endif
