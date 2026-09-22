#include <klib.h>
#include <klib-macros.h>
#include <errno.h>
#include <limits.h>
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
size_t strnlen(const char *s, size_t maxlen)
{
  size_t len = 0;
  while (len < maxlen && s[len] != '\0')
  {
    len++;
  }
  return len;
}

char *(strchr)(const char *s, int c)
{
  const char target = (char)c;
  while (1)
  {
    if (*s == target)
    {
      return (char *)s;
    }
    if (*s == '\0')
    {
      return NULL;
    }
    s++;
  }
}

char *(strrchr)(const char *s, int c)
{
  const char target = (char)c;
  const char *last = NULL;
  do
  {
    if (*s == target)
    {
      last = s;
    }
  } while (*s++ != '\0');
  return (char *)last;
}

char *(strstr)(const char *haystack, const char *needle)
{
  if (*needle == '\0')
  {
    return (char *)haystack;
  }
  for (; *haystack != '\0'; haystack++)
  {
    const char *candidate = haystack;
    const char *pattern = needle;
    while (*pattern != '\0' && *candidate == *pattern)
    {
      candidate++;
      pattern++;
    }
    if (*pattern == '\0')
    {
      return (char *)haystack;
    }
  }
  return NULL;
}

static int string_set_contains(const char *set, unsigned char target)
{
  const unsigned char *bytes = (const unsigned char *)set;
  while (*bytes != '\0')
  {
    if (*bytes == target)
    {
      return 1;
    }
    bytes++;
  }
  return 0;
}

size_t strspn(const char *s, const char *accept)
{
  const unsigned char *bytes = (const unsigned char *)s;
  size_t length = 0;
  while (bytes[length] != '\0' &&
         string_set_contains(accept, bytes[length]))
  {
    length++;
  }
  return length;
}

size_t strcspn(const char *s, const char *reject)
{
  const unsigned char *bytes = (const unsigned char *)s;
  size_t length = 0;
  while (bytes[length] != '\0' &&
         !string_set_contains(reject, bytes[length]))
  {
    length++;
  }
  return length;
}

char *(strpbrk)(const char *s, const char *accept)
{
  const unsigned char *bytes = (const unsigned char *)s;
  while (*bytes != '\0')
  {
    if (string_set_contains(accept, *bytes))
    {
      return (char *)bytes;
    }
    bytes++;
  }
  return NULL;
}

char *strtok_r(char *__restrict s, const char *__restrict delim,
               char **__restrict saveptr)
{
  char *current = s != NULL ? s : *saveptr;
  if (current == NULL)
  {
    return NULL;
  }

  current += strspn(current, delim);
  if (*current == '\0')
  {
    *saveptr = current;
    return NULL;
  }

  char *end = current + strcspn(current, delim);
  if (*end != '\0')
  {
    *end = '\0';
    *saveptr = end + 1;
  }
  else
  {
    *saveptr = end;
  }
  return current;
}

char *strtok(char *__restrict s, const char *__restrict delim)
{
  static char *next;
  return strtok_r(s, delim, &next);
}

char *strsep(char **stringp, const char *delim)
{
  char *token = *stringp;
  if (token == NULL)
  {
    return NULL;
  }

  char *end = strpbrk(token, delim);
  if (end != NULL)
  {
    *end = '\0';
    *stringp = end + 1;
  }
  else
  {
    *stringp = NULL;
  }
  return token;
}

static unsigned char ascii_tolower(unsigned char c)
{
  if (c >= (unsigned char)'A' && c <= (unsigned char)'Z')
  {
    return (unsigned char)(c + ((unsigned char)'a' - (unsigned char)'A'));
  }
  return c;
}

int strcasecmp(const char *s1, const char *s2)
{
  while (1)
  {
    const unsigned char c1 = ascii_tolower((unsigned char)*s1);
    const unsigned char c2 = ascii_tolower((unsigned char)*s2);
    if (c1 != c2 || c1 == '\0')
    {
      return (int)c1 - (int)c2;
    }
    s1++;
    s2++;
  }
}

int strncasecmp(const char *s1, const char *s2, size_t n)
{
  for (size_t i = 0; i < n; i++)
  {
    const unsigned char c1 = ascii_tolower((unsigned char)s1[i]);
    const unsigned char c2 = ascii_tolower((unsigned char)s2[i]);
    if (c1 != c2 || c1 == '\0')
    {
      return (int)c1 - (int)c2;
    }
  }
  return 0;
}

char *strdup(const char *s)
{
  const size_t length = strlen(s);
  if (length == SIZE_MAX)
  {
    errno = ENOMEM;
    return NULL;
  }

  char *copy = (char *)malloc(length + 1);
  if (copy != NULL)
  {
    memcpy(copy, s, length + 1);
  }
  return copy;
}

char *strndup(const char *s, size_t n)
{
  const size_t length = strnlen(s, n);
  if (length == SIZE_MAX)
  {
    errno = ENOMEM;
    return NULL;
  }

  char *copy = (char *)malloc(length + 1);
  if (copy != NULL)
  {
    memcpy(copy, s, length);
    copy[length] = '\0';
  }
  return copy;
}

void *memmem(const void *haystack, size_t haystacklen,
             const void *needle, size_t needlelen)
{
  if (needlelen == 0)
  {
    return (void *)haystack;
  }
  if (needlelen > haystacklen)
  {
    return NULL;
  }

  const unsigned char *haystack_bytes =
      (const unsigned char *)haystack;
  const unsigned char *needle_bytes = (const unsigned char *)needle;
  const size_t last = haystacklen - needlelen;
  for (size_t i = 0; i <= last; i++)
  {
    if (haystack_bytes[i] == needle_bytes[0] &&
        memcmp(haystack_bytes + i, needle_bytes, needlelen) == 0)
    {
      return (void *)(haystack_bytes + i);
    }
  }
  return NULL;
}

ptrdiff_t (strscpy)(char *dst, const char *src, size_t dstsize)
{
  if (dstsize == 0 || dstsize > INT_MAX)
  {
    return -E2BIG;
  }
  size_t copied = 0;
  while (copied < dstsize - 1 && src[copied] != '\0')
  {
    dst[copied] = src[copied];
    copied++;
  }
  dst[copied] = '\0';
  if (src[copied] != '\0')
  {
    return -E2BIG;
  }
  return (ptrdiff_t)copied;
}

size_t (strlcpy)(char *dst, const char *src, size_t dstsize)
{
  size_t src_len = strlen(src);
  if (dstsize != 0)
  {
    size_t copied = src_len < dstsize - 1 ? src_len : dstsize - 1;
    memcpy(dst, src, copied);
    dst[copied] = '\0';
  }
  return src_len;
}

size_t (strlcat)(char *dst, const char *src, size_t dstsize)
{
  size_t dst_len = strnlen(dst, dstsize);
  size_t src_len = strlen(src);
  if (dst_len == dstsize)
  {
    return dstsize + src_len;
  }

  size_t available = dstsize - dst_len - 1;
  size_t copied = src_len < available ? src_len : available;
  memcpy(dst + dst_len, src, copied);
  dst[dst_len + copied] = '\0';
  return dst_len + src_len;
}

char *strcpy(char *dst, const char *src)
{
  // panic("Not implemented");
  _strcpy_to_end(dst, src);
  return dst;
}

char *stpcpy(char *dst, const char *src)
{
  return _strcpy_to_end(dst, src);
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

char *stpncpy(char *dst, const char *src, size_t n)
{
  if (n == 0)
  {
    return dst;
  }

  size_t copied = 0;
  while (copied < n && src[copied] != '\0')
  {
    dst[copied] = src[copied];
    copied++;
  }

  char *end = dst + copied;
  while (copied < n)
  {
    dst[copied++] = '\0';
  }
  return end;
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

char *strncat(char *dst, const char *src, size_t n)
{
  char *ret = dst;
  while (*dst != '\0')
  {
    dst++;
  }

  size_t copied = 0;
  while (copied < n && src[copied] != '\0')
  {
    dst[copied] = src[copied];
    copied++;
  }
  dst[copied] = '\0';
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
  unsigned char *destination = (unsigned char *)dst;
  const unsigned char *source = (const unsigned char *)src;
  uintptr_t destination_address = (uintptr_t)destination;
  uintptr_t source_address = (uintptr_t)source;
  if (destination_address > source_address &&
      destination_address - source_address < n)
  {
    for (size_t i = n; i > 0; i--)
    {
      destination[i - 1] = source[i - 1];
    }
  }
  else if (destination_address != source_address)
  {
    for (size_t i = 0; i < n; i++)
    {
      destination[i] = source[i];
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

void *mempcpy(void *dst, const void *src, size_t n)
{
  if (n == 0)
  {
    return dst;
  }
  memcpy(dst, src, n);
  return (unsigned char *)dst + n;
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

void *(memchr)(const void *s, int c, size_t n)
{
  const unsigned char *bytes = (const unsigned char *)s;
  const unsigned char target = (unsigned char)c;
  for (size_t i = 0; i < n; i++)
  {
    if (bytes[i] == target)
    {
      return (void *)(bytes + i);
    }
  }
  return NULL;
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
