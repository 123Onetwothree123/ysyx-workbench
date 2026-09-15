#include <am.h>
#include <klib.h>
#include <klib-macros.h>
#include <ctype.h>
#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
int isalnum(int c)
{
  return isalpha(c) || isdigit(c);
}
int isalpha(int c)
{
  // |32可以直接把大写字母低5位对齐到小写，一次比较罩住的这些52个字母
  return ((unsigned)c | 32) - 'a' < 26;
}
int isblank(int c)
{
  // C locale的blank只有2个：空格和水平制表符，\v和\f不算
  return c == ' ' || c == '\t';
}
int iscntrl(int c)
{
  // 控制字符：0x00~0x1F和0x7F（DEL）
  return (unsigned)c < 0x20 || c == 0x7f;
}
int isdigit(int c)
{
  return (unsigned)c - '0' < 10;
}
int isgraph(int c)
{
  // 可见字符：0x21~0x7E（可打印但不含空格），然后查询AI的时候，AI建议是16进制，所以是共0x5e个
  return (unsigned)c - 0x21 < 0x5e;
}
int islower(int c)
{
  return (unsigned)c - 'a' < 26;
}
int isprint(int c)
{
  // 可打印：0x20到0x7E（isgraph加个空格），共0x5f个
  return (unsigned)c - 0x20 < 0x5f;
}
int ispunct(int c)
{
  // 可见字符里除掉字母数字剩下的是这些标点符号
  return isgraph(c) && !isalnum(c);
}
int isspace(int c)
{
  // 问了AI，这里的C locale 空白就 6 个：' ' 和 '\t'~'\r'（\t\n\v\f\r）
  return c == ' ' || (unsigned)c - '\t' < 5;
}
int isupper(int c)
{
  return (unsigned)c - 'A' < 26;
}
int isxdigit(int c)
{
  // 十六进制：数字或到a到f（|32大小写对齐后<6）
  return isdigit(c) || (((unsigned)c | 32) - 'a' < 6);
}
int tolower(int c)
{
  if (isupper(c))
  {
    return c | 32;
  }
  return c;
}
int toupper(int c)
{
  if (islower(c))
  {
    return c & 0x5f;
  }
  return c;
}

#endif
