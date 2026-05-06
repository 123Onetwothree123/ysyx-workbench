#include <am.h>
#include <klib.h>
#include <klib-macros.h>
#include <stdarg.h>

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
static size_t console_write(FILE *f, const unsigned char *s, size_t l); // 这是底层输出的
static FILE __stdout_FILE = {
    .lbf = EOF,
    .lock = -1,
    .write = console_write,
    .cookie = NULL,
    .buf = NULL,
    .wbase = NULL,
    .wpos = NULL,
    .wend = NULL,
    .count = 0,
    .err = 0,
};
FILE *stdout = &__stdout_FILE;
#define MIN(a, b) ((a) < (b) ? (a) : (b))

// 自己写的
static size_t sn_write(FILE *f, const unsigned char *s, size_t l);
/*
static void byte_copy(char *dst, const unsigned char *src, size_t n);
static size_t cstr_len(const char *s);
*/
int vfprintf(FILE *f, const char *fmt, va_list ap);
// vfprintf和write之间的中间层，就反正是vfprintf不直接碰sn_write，然后具体怎么写就直接让f->write决定
static int file_write(FILE *f, const void *buf, size_t len);
static int file_putc(FILE *f, char ch);       // file_write的1字节包装
//static int file_pad(FILE *f, char ch, int n); // 连续输出多个填充字符
// 负责把一个无符号整数转成字符串，但先倒着存，x是待转换的数值，base是基数，buf是缓冲区，返回 int 类型的实际写入字符个数，即数字的位数
// static int ull_to_rev(unsigned long long x, unsigned base, char *buf);
/*
太长了，我先用多行注释来记录一下，以免忘记
目的：以unsigned long long形式传入，将一个无符号整数格式化成指定进制、指定宽度、带前缀、支持负数符号等，并写入文件流
x：数字本体
base：10或16
width：最小宽度
zero_pad：是否用0填充
prefix/prefix_len：比如%p需要"0x"
negative：是否要先输出-
*/
// static int file_put_uint(FILE *f, unsigned long long x, unsigned base, int width, int zero_pad, const char *prefix, int prefix_len, int negative);
//  解析十进制宽度，比如%08x这里里面的这个8，%123d的这个123，然后因为传入的是fmt指针的地址，所以函数内部可以一边读一边推进指针
// static int parse_width(const char **ps);
static int printf_core(FILE *f, const char *fmt, va_list *ap);
static int print_str(FILE *f, const char *s);
static int print_uint(FILE *f, unsigned long long x, unsigned base);
static int print_int(FILE *f, long long x);
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
  return vsnprintf(out, INT_MAX, fmt, ap);
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
static int file_write(FILE *f, const void *buf, size_t len)
{
  if (len == 0)
  {
    return 0;
  }
  if (f->err)
  {
    return -1;
  }
  if (f->write(f, (const unsigned char *)buf, len) != len)
  {
    f->err = 1;
    return -1;
  }
  f->count += len;
  return 0;
}
static int file_putc(FILE *f, char ch)
{
  return file_write(f, &ch, 1);
}
/*
static int file_pad(FILE *f, char ch, int n)
{
  while (n-- > 0)
  {
    if (file_putc(f, ch) < 0)
      return -1;
  }
  return 0;
}
  */
/*
static int ull_to_rev(unsigned long long x, unsigned base, char *buf)
{
  static const char dig[] = "0123456789abcdef"; // 这是为了既支持2进制，也支持16进制
  int n = 0;                                    // 拿来记录已生成的字符数的
  //他妈的用dowhile是为了保证即使x是0，也会至少执行一次循环，将'0'写入缓冲区，目的就是为了到时候可以将0转换为字符串
  do
  {
    buf[n++] = dig[x % base]; // x % base是为了取出当前最低位数字，就是0到base-1之间
    x /= base;                // 丢弃最低位
  } while (x != 0);
  return n;
}
*/
/*
static int file_put_uint(FILE *f, unsigned long long x, unsigned base, int width, int zero_pad, const char *prefix, int prefix_len, int negative)
{
  // char tmp[32];                                       // 缓冲区，用于存放转换后的数字字符，但是得是倒序的
  char tmp[64];
  int ndig = ull_to_rev(x, base, tmp);                // x在指定base下的数字位数，但是不含前缀和符号
  int total = ndig + prefix_len + (negative ? 1 : 0); // 格式化后的总字符数，但是只是最小字符数，实际上可能会更多
  // 逆序存储是为了方便后续从后向前输出，避免额外的缓冲区反转操作
  // 如果不是补零，就先补空格
  if (!zero_pad && width > total) // 如果要求的最小宽度width比实际内容total大，并且不是0填充
  {
    if (file_pad(f, ' ', width - total) < 0) // 在前面补空格
    {
      return -1;
    }
  }
  if (negative) // 如果需要负号，先输出-
  {
    if (file_putc(f, '-') < 0)
    {
      return -1;
    }
  }
  // 如果有前缀，比如0x，就在这里输出
  if (prefix_len)
  {
    if (file_write(f, prefix, prefix_len) < 0)
    {
      return -1;
    }
  }
  // 如果是补零，就在这里补0
  if (zero_pad && width > total)
  {
    // 反正就是当启用了zero_pad的时候，并且width大于内容总长度，就在前缀之后、数字之前补0
    if (file_pad(f, '0', width - total) < 0)
    {
      return -1;
    }
  }
  while (ndig > 0) // 把数字正序输出
  {
    if (file_putc(f, tmp[--ndig]) < 0)
    {
      return -1;
    }
  }
  return 0;
}
*/
/*
static int parse_width(const char **ps)
{
  int width = 0;
  // 只要当前字符还是数字，就继续累计
  while (**ps >= '0' && **ps <= '9')
  {
    width = width * 10 + (**ps - '0');
    (*ps)++;
  }
  return width;
}
*/
static int print_str(FILE *f, const char *s)
{
  if (s == NULL)
  {
    s = "(null)";
  }
  while (*s)
  {
    if (file_putc(f, *s++) < 0)
    {
      return -1;
    }
  }
  return 0;
}
static int print_uint(FILE *f, unsigned long long x, unsigned base)
{
  static const char digits[] = "0123456789abcdef";
  char buf[32];
  int n = 0;
  do
  {
    buf[n++] = digits[x % base];
    x /= base;
  } while (x != 0);
  while (n > 0)
  {
    if (file_putc(f, buf[--n]) < 0)
    {
      return -1;
    }
  }
  return 0;
}
static int print_int(FILE *f, long long x)
{
  if (x < 0)
  {
    if (file_putc(f, '-') < 0)
    {
      return -1;
    }
    return print_uint(f, 0ull - (unsigned long long)x, 10);
  }
  return print_uint(f, (unsigned long long)x, 10);
}
static int printf_core(FILE *f, const char *fmt, va_list *ap)
{
  while (*fmt)
  {
    // 他妈的烦了，普通字符直接输出
    if (*fmt != '%')
    {
      if (file_putc(f, *fmt) < 0)
      {
        return -1; // 懒得管了，直接退出
      }
      fmt++;
      continue;
    }
    // 跳过%，然后这就直接看真正的格式符
    fmt++;
    switch (*fmt)
    {
    case '%':
      if (file_putc(f, '%') < 0) // 妈的简单起见,不想管了
      {
        return -1;
      }
      break;
    case 'c':
      if (file_putc(f, (char)va_arg(*ap, int)) < 0)
      {
        return -1;
      }
      break;
    case 's':
      if (print_str(f, va_arg(*ap, const char *)) < 0)
      {
        return -1;
      }
      break;
    case 'd':
    case 'i':
      if (print_int(f, va_arg(*ap, int)) < 0)
      {
        return -1;
      }
      break;
    case 'u':
      if (print_uint(f, va_arg(*ap, unsigned int), 10) < 0)
      {
        return -1;
      }
      break;

    case 'x':
      if (print_uint(f, va_arg(*ap, unsigned int), 16) < 0)
      {
        return -1;
      }
      break;
      /*
      //妈的，指针问题到现在还没有一个可行的方案，他妈的烦死了，一直报错，一直报错，这个傻逼vscode还虚报错误，脑子有坑
    case 'p':
      if (file_write(f, "0x", 2) < 0)
      {
        return -1;
      }
      if (print_uint(f, (uintptr_t)va_arg(*ap, void *), 16) < 0)
      {
        return -1;
      }
      break;
      */
    case '\0':
      // 反正不管怎么样，格式串最后如果是单独一个%，就把它直接就原样输出
      if (file_putc(f, '%') < 0)
      {
        return -1;
      }
      return f->err ? -1 : (int)f->count;
    default:
      // GPT5.4的建议是不认识的格式，原样吐回去，方便调试
      if (file_putc(f, '%') < 0)
      {
        return -1;
      }
      if (file_putc(f, *fmt) < 0)
      {
        return -1;
      }
      break;
    }
    fmt++;
  }
  return f->err ? -1 : (int)f->count;
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
int fprintf(FILE *stream, const char *fmt, ...)
{
  int ret;
  va_list ap;
  va_start(ap, fmt);
  ret = vfprintf(stream, fmt, ap);
  va_end(ap);
  return ret;
}
#endif
