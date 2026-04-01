#include <klib.h>
#include <klib-macros.h>
#include <stdint.h>

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)

// 自己写的函数：
static char *_strcpy_to_end(char *dst, const char *src);

size_t strlen(const char *s)
{
  // panic("Not implemented");
  const char *start = s;
  while (*s)
  {
    s++;
  }
  return s - start;
}
char *strcpy(char *dst, const char *src)
{
  // panic("Not implemented");
  _strcpy_to_end(dst, src);
  return dst;
}

char *strncpy(char *dst, const char *src, size_t n)
{
  // panic("Not implemented");
  char *ret = dst;
  size_t i;
  for (i = 0; i < n && src[i] != '\0'; i++)
  {
    dst[i] = src[i];
  }
  for (; i < n; i++)
  {
    dst[i] = '\0';
  }
  return ret;
}

char *strcat(char *dst, const char *src)
{
  // panic("Not implemented");
  char *ret = dst;
  // 先找到dst的末尾再说
  while (*dst)
  {
    dst++;
  }
  _strcpy_to_end(dst, src);
  return ret;
}

int strcmp(const char *s1, const char *s2)
{
  // panic("Not implemented");
  while (*s1 && (*s1 == *s2))
  {
    s1++;
    s2++;
  }
  return *(unsigned char *)s1 - *(unsigned char *)s2;
}

int strncmp(const char *s1, const char *s2, size_t n)
{
  // panic("Not implemented");
  for (size_t i = 0; i < n; i++)
  {
    if (*s1 != *s2 || *s1 == '\0')
    {
      return *(unsigned char *)s1 - *(unsigned char *)s2;
    }
    s1++;
    s2++;
  }
  return 0; // 前 n 个字符都相同
}

void *memset(void *s, int c, size_t n)
{
  // panic("Not implemented");
  unsigned char *destination = (unsigned char *)s;
  unsigned char source = (unsigned char)c;
  for (size_t i = 0; i < n; i++)
  {
    destination[i] = source;
  }
  return s;
}

void *memmove(void *dst, const void *src, size_t n)
{
  // panic("Not implemented");
  unsigned char *destination = (unsigned char *)dst;
  unsigned char *source = (unsigned char *)src;
  if (destination < source)
  {
    for (size_t i = 0; i < n; i++)
    {
      destination[i] = source[i];
    }
  }
  else if (destination > source)
  {
    for (size_t i = n; i > 0; i--)
    {
      destination[i - 1] = source[i - 1];
    }
  }
  return dst;
}

void *memcpy(void *out, const void *in, size_t n)
{
  // panic("Not implemented");
  unsigned char *FunctionDestinationOut = (unsigned char *)out; // 因为memcpy是按字节操作的，所以转成unsigned char指针
  unsigned char *FunctionSourceIn = (unsigned char *)in;
  for (size_t i = 0; i < n; i++)
  {
    FunctionDestinationOut[i] = FunctionSourceIn[i];
  }
  return out;
}

int memcmp(const void *s1, const void *s2, size_t n)
{
  // panic("Not implemented");
  unsigned char *FunctionS1 = (unsigned char *)s1;
  unsigned char *FunctionS2 = (unsigned char *)s2;
  for (size_t i = 0; i < n; i++)
  {
    if (*FunctionS1 == *FunctionS2)
    {
    }
    else if (*FunctionS1 != *FunctionS2)
    {
      return *FunctionS1 - *FunctionS2;
    }
    FunctionS1++;
    FunctionS2++;
  }
  return 0;
}

// 自己写的
static char *_strcpy_to_end(char *dst, const char *src)
{
  while ((*dst++ = *src++) != '\0')
  {
    // 所有操作都在条件表达式中完成
  }
  return dst - 1; // 返回指向 '\0' 的位置
}
#endif
