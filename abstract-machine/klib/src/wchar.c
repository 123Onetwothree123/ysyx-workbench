#include <wchar.h>

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)

#include <errno.h>
#include <klib.h>
#include <stdint.h>
#include <time.h>

/* ISO C specifies a distinct internal state object for every restartable
 * conversion function when the caller passes a null state pointer. */
static mbstate_t internal_mbrtowc_state;
static mbstate_t internal_mbrlen_state;
static mbstate_t internal_wcrtomb_state;
static mbstate_t internal_mbsrtowcs_state;
static mbstate_t internal_wcsrtombs_state;

static void reset_mbstate(mbstate_t *state)
{
  if (state != NULL)
  {
    state->__count = 0;
    state->__value.__wch = 0;
  }
}

static int wide_set_contains(const wchar_t *set, wchar_t target)
{
  while (*set != L'\0')
  {
    if (*set == target)
    {
      return 1;
    }
    set++;
  }
  return 0;
}

size_t wcslen(const wchar_t *s)
{
  const wchar_t *start = s;
  while (*s != L'\0')
  {
    s++;
  }
  return (size_t)(s - start);
}

size_t wcsnlen(const wchar_t *s, size_t maxlen)
{
  size_t length = 0;
  while (length < maxlen && s[length] != L'\0')
  {
    length++;
  }
  return length;
}

wchar_t *wcscpy(wchar_t *dst, const wchar_t *src)
{
  wchar_t *result = dst;
  while ((*dst++ = *src++) != L'\0')
  {
  }
  return result;
}

wchar_t *wcsncpy(wchar_t *dst, const wchar_t *src, size_t n)
{
  size_t copied = 0;
  while (copied < n && src[copied] != L'\0')
  {
    dst[copied] = src[copied];
    copied++;
  }
  while (copied < n)
  {
    dst[copied++] = L'\0';
  }
  return dst;
}

wchar_t *wcscat(wchar_t *dst, const wchar_t *src)
{
  wchar_t *result = dst;
  while (*dst != L'\0')
  {
    dst++;
  }
  wcscpy(dst, src);
  return result;
}

wchar_t *wcsncat(wchar_t *dst, const wchar_t *src, size_t n)
{
  wchar_t *result = dst;
  while (*dst != L'\0')
  {
    dst++;
  }

  size_t copied = 0;
  while (copied < n && src[copied] != L'\0')
  {
    dst[copied] = src[copied];
    copied++;
  }
  dst[copied] = L'\0';
  return result;
}

int wcscmp(const wchar_t *left, const wchar_t *right)
{
  while (*left == *right && *left != L'\0')
  {
    left++;
    right++;
  }
  return (*left > *right) - (*left < *right);
}

int wcsncmp(const wchar_t *left, const wchar_t *right, size_t n)
{
  for (size_t i = 0; i < n; i++)
  {
    if (*left != *right || *left == L'\0')
    {
      return (*left > *right) - (*left < *right);
    }
    left++;
    right++;
  }
  return 0;
}

wchar_t *wcschr(const wchar_t *s, wchar_t c)
{
  while (1)
  {
    if (*s == c)
    {
      return (wchar_t *)s;
    }
    if (*s == L'\0')
    {
      return NULL;
    }
    s++;
  }
}

wchar_t *wcsrchr(const wchar_t *s, wchar_t c)
{
  const wchar_t *last = NULL;
  do
  {
    if (*s == c)
    {
      last = s;
    }
  } while (*s++ != L'\0');
  return (wchar_t *)last;
}

wchar_t *wcsstr(const wchar_t *haystack, const wchar_t *needle)
{
  if (*needle == L'\0')
  {
    return (wchar_t *)haystack;
  }

  while (*haystack != L'\0')
  {
    const wchar_t *candidate = haystack;
    const wchar_t *pattern = needle;
    while (*pattern != L'\0' && *candidate == *pattern)
    {
      candidate++;
      pattern++;
    }
    if (*pattern == L'\0')
    {
      return (wchar_t *)haystack;
    }
    haystack++;
  }
  return NULL;
}

size_t wcsspn(const wchar_t *s, const wchar_t *accept)
{
  size_t length = 0;
  while (s[length] != L'\0' &&
         wide_set_contains(accept, s[length]))
  {
    length++;
  }
  return length;
}

size_t wcscspn(const wchar_t *s, const wchar_t *reject)
{
  size_t length = 0;
  while (s[length] != L'\0' &&
         !wide_set_contains(reject, s[length]))
  {
    length++;
  }
  return length;
}

wchar_t *wcspbrk(const wchar_t *s, const wchar_t *accept)
{
  while (*s != L'\0')
  {
    if (wide_set_contains(accept, *s))
    {
      return (wchar_t *)s;
    }
    s++;
  }
  return NULL;
}

wchar_t *wcstok(wchar_t *s, const wchar_t *delim, wchar_t **saveptr)
{
  if (saveptr == NULL)
  {
    return NULL;
  }

  wchar_t *current = s != NULL ? s : *saveptr;
  if (current == NULL)
  {
    return NULL;
  }

  current += wcsspn(current, delim);
  if (*current == L'\0')
  {
    *saveptr = current;
    return NULL;
  }

  wchar_t *end = current + wcscspn(current, delim);
  if (*end != L'\0')
  {
    *end = L'\0';
    *saveptr = end + 1;
  }
  else
  {
    *saveptr = end;
  }
  return current;
}

wchar_t *wmemchr(const wchar_t *s, wchar_t c, size_t n)
{
  for (size_t i = 0; i < n; i++)
  {
    if (s[i] == c)
    {
      return (wchar_t *)(s + i);
    }
  }
  return NULL;
}

int wmemcmp(const wchar_t *left, const wchar_t *right, size_t n)
{
  for (size_t i = 0; i < n; i++)
  {
    if (left[i] != right[i])
    {
      return (left[i] > right[i]) - (left[i] < right[i]);
    }
  }
  return 0;
}

wchar_t *wmemcpy(wchar_t *dst, const wchar_t *src, size_t n)
{
  for (size_t i = 0; i < n; i++)
  {
    dst[i] = src[i];
  }
  return dst;
}

wchar_t *wmemmove(wchar_t *dst, const wchar_t *src, size_t n)
{
  const uintptr_t dst_address = (uintptr_t)dst;
  const uintptr_t src_address = (uintptr_t)src;
  if (dst_address > src_address &&
      (dst_address - src_address) / sizeof(wchar_t) < n)
  {
    for (size_t i = n; i > 0; i--)
    {
      dst[i - 1] = src[i - 1];
    }
  }
  else if (dst_address != src_address)
  {
    for (size_t i = 0; i < n; i++)
    {
      dst[i] = src[i];
    }
  }
  return dst;
}

wchar_t *wmemset(wchar_t *dst, wchar_t c, size_t n)
{
  for (size_t i = 0; i < n; i++)
  {
    dst[i] = c;
  }
  return dst;
}

int wcscoll(const wchar_t *left, const wchar_t *right)
{
  return wcscmp(left, right);
}

size_t wcsxfrm(wchar_t *dst, const wchar_t *src, size_t n)
{
  const size_t length = wcslen(src);
  if (n != 0)
  {
    const size_t copied = length < n ? length + 1 : n;
    wmemcpy(dst, src, copied);
  }
  return length;
}

size_t wcsftime(
    wchar_t *dst,
    size_t maxsize,
    const wchar_t *format,
    const struct tm *timeptr)
{
  if (format == NULL || timeptr == NULL || (dst == NULL && maxsize != 0))
  {
    errno = EINVAL;
    return 0;
  }
  if (maxsize == 0) return 0;

  size_t written = 0;
  const wchar_t *current = format;
  while (*current != L'\0')
  {
    if (*current != L'%')
    {
      if (written + 1 >= maxsize) goto failure;
      dst[written++] = *current++;
      continue;
    }

    char narrow_format[4];
    size_t format_length = 0;
    narrow_format[format_length++] = '%';
    current++;
    if (*current == L'E' || *current == L'O')
    {
      narrow_format[format_length++] = (char)*current++;
    }
    if (*current == L'\0' || (uintmax_t)*current > 0x7f)
    {
      goto failure;
    }
    narrow_format[format_length++] = (char)*current++;
    narrow_format[format_length] = '\0';

    char narrow_output[128] = {0};
    size_t length = strftime(
        narrow_output, sizeof(narrow_output), narrow_format, timeptr);
    if (length == 0 || length >= sizeof(narrow_output) ||
        length >= maxsize || written > maxsize - length - 1)
    {
      goto failure;
    }
    for (size_t i = 0; i < length; i++)
    {
      dst[written++] = (wchar_t)(unsigned char)narrow_output[i];
    }
  }
  dst[written] = L'\0';
  return written;

failure:
  dst[0] = L'\0';
  return 0;
}

typedef enum
{
  WIDE_INTEGER_LONG,
  WIDE_INTEGER_UNSIGNED_LONG,
  WIDE_INTEGER_LONG_LONG,
  WIDE_INTEGER_UNSIGNED_LONG_LONG,
} WideIntegerKind;

typedef union
{
  long signed_long;
  unsigned long unsigned_long;
  long long signed_long_long;
  unsigned long long unsigned_long_long;
} WideIntegerResult;

static WideIntegerResult parse_wide_integer(
    const wchar_t *input,
    wchar_t **endptr,
    int base,
    WideIntegerKind kind)
{
  WideIntegerResult result = {.unsigned_long_long = 0};
  if (input == NULL)
  {
    errno = EINVAL;
    if (endptr != NULL) *endptr = NULL;
    return result;
  }
  size_t length = wcslen(input);
  if (length == SIZE_MAX)
  {
    errno = EOVERFLOW;
    if (endptr != NULL) *endptr = (wchar_t *)input;
    return result;
  }
  char *narrow = (char *)malloc(length + 1);
  if (narrow == NULL)
  {
    errno = ENOMEM;
    if (endptr != NULL) *endptr = (wchar_t *)input;
    return result;
  }
  size_t copied = 0;
  while (copied < length && (uintmax_t)input[copied] <= 0x7f)
  {
    narrow[copied] = (char)input[copied];
    copied++;
  }
  narrow[copied] = '\0';

  char *narrow_end = narrow;
  switch (kind)
  {
  case WIDE_INTEGER_LONG:
    result.signed_long = strtol(narrow, &narrow_end, base);
    break;
  case WIDE_INTEGER_UNSIGNED_LONG:
    result.unsigned_long = strtoul(narrow, &narrow_end, base);
    break;
  case WIDE_INTEGER_LONG_LONG:
    result.signed_long_long = strtoll(narrow, &narrow_end, base);
    break;
  default:
    result.unsigned_long_long = strtoull(narrow, &narrow_end, base);
    break;
  }
  if (endptr != NULL)
  {
    *endptr = (wchar_t *)input + (size_t)(narrow_end - narrow);
  }
  free(narrow);
  return result;
}

long wcstol(const wchar_t *input, wchar_t **endptr, int base)
{
  return parse_wide_integer(
      input, endptr, base, WIDE_INTEGER_LONG).signed_long;
}

unsigned long wcstoul(const wchar_t *input, wchar_t **endptr, int base)
{
  return parse_wide_integer(
      input, endptr, base, WIDE_INTEGER_UNSIGNED_LONG).unsigned_long;
}

long long wcstoll(const wchar_t *input, wchar_t **endptr, int base)
{
  return parse_wide_integer(
      input, endptr, base, WIDE_INTEGER_LONG_LONG).signed_long_long;
}

unsigned long long wcstoull(
    const wchar_t *input,
    wchar_t **endptr,
    int base)
{
  return parse_wide_integer(
      input, endptr, base,
      WIDE_INTEGER_UNSIGNED_LONG_LONG).unsigned_long_long;
}

wint_t btowc(int c)
{
  if (c < 0 || c > 0x7f)
  {
    return WEOF;
  }
  return (wint_t)(unsigned int)c;
}

int wctob(wint_t c)
{
  return c <= (wint_t)0x7f ? (int)c : EOF;
}

int mbsinit(const mbstate_t *state)
{
  return state == NULL || state->__count == 0;
}

size_t mbrtowc(wchar_t *pwc, const char *s, size_t n, mbstate_t *state)
{
  mbstate_t *active_state =
      state != NULL ? state : &internal_mbrtowc_state;
  if (s == NULL)
  {
    reset_mbstate(active_state);
    return 0;
  }
  if (n == 0)
  {
    return (size_t)-2;
  }

  const unsigned char byte = (unsigned char)s[0];
  if (byte > 0x7f)
  {
    reset_mbstate(active_state);
    errno = EILSEQ;
    return (size_t)-1;
  }

  if (pwc != NULL)
  {
    *pwc = (wchar_t)byte;
  }
  reset_mbstate(active_state);
  return byte == 0 ? 0 : 1;
}

size_t mbrlen(const char *s, size_t n, mbstate_t *state)
{
  return mbrtowc(NULL, s, n,
                 state != NULL ? state : &internal_mbrlen_state);
}

size_t wcrtomb(char *s, wchar_t wc, mbstate_t *state)
{
  mbstate_t *active_state =
      state != NULL ? state : &internal_wcrtomb_state;
  if (s == NULL)
  {
    reset_mbstate(active_state);
    return 1;
  }
  if ((uintmax_t)wc > 0x7f)
  {
    reset_mbstate(active_state);
    errno = EILSEQ;
    return (size_t)-1;
  }

  s[0] = (char)wc;
  reset_mbstate(active_state);
  return 1;
}

size_t mbsrtowcs(wchar_t *dst, const char **src, size_t len,
                 mbstate_t *state)
{
  mbstate_t *active_state =
      state != NULL ? state : &internal_mbsrtowcs_state;
  if (src == NULL || *src == NULL)
  {
    return 0;
  }

  const char *input = *src;
  size_t converted = 0;
  if (dst == NULL)
  {
    while (input[converted] != '\0')
    {
      if ((unsigned char)input[converted] > 0x7f)
      {
        errno = EILSEQ;
        return (size_t)-1;
      }
      converted++;
    }
    return converted;
  }

  while (converted < len)
  {
    const unsigned char byte = (unsigned char)*input;
    if (byte > 0x7f)
    {
      *src = input;
      reset_mbstate(active_state);
      errno = EILSEQ;
      return (size_t)-1;
    }
    if (byte == 0)
    {
      dst[converted] = L'\0';
      *src = NULL;
      reset_mbstate(active_state);
      return converted;
    }
    dst[converted++] = (wchar_t)byte;
    input++;
  }

  *src = input;
  reset_mbstate(active_state);
  return converted;
}

size_t wcsrtombs(char *dst, const wchar_t **src, size_t len,
                 mbstate_t *state)
{
  mbstate_t *active_state =
      state != NULL ? state : &internal_wcsrtombs_state;
  if (src == NULL || *src == NULL)
  {
    return 0;
  }

  const wchar_t *input = *src;
  size_t converted = 0;
  if (dst == NULL)
  {
    while (input[converted] != L'\0')
    {
      if ((uintmax_t)input[converted] > 0x7f)
      {
        errno = EILSEQ;
        return (size_t)-1;
      }
      converted++;
    }
    return converted;
  }

  while (converted < len)
  {
    const wchar_t wc = *input;
    if ((uintmax_t)wc > 0x7f)
    {
      *src = input;
      reset_mbstate(active_state);
      errno = EILSEQ;
      return (size_t)-1;
    }
    if (wc == L'\0')
    {
      dst[converted] = '\0';
      *src = NULL;
      reset_mbstate(active_state);
      return converted;
    }
    dst[converted++] = (char)wc;
    input++;
  }

  *src = input;
  reset_mbstate(active_state);
  return converted;
}

int mblen(const char *s, size_t n)
{
  static mbstate_t state;
  if (s == NULL)
  {
    reset_mbstate(&state);
    return 0;
  }

  const size_t result = mbrlen(s, n, &state);
  if (result == (size_t)-1 || result == (size_t)-2)
  {
    errno = EILSEQ;
    return -1;
  }
  return (int)result;
}

int mbtowc(wchar_t *pwc, const char *s, size_t n)
{
  static mbstate_t state;
  if (s == NULL)
  {
    reset_mbstate(&state);
    return 0;
  }

  const size_t result = mbrtowc(pwc, s, n, &state);
  if (result == (size_t)-1 || result == (size_t)-2)
  {
    errno = EILSEQ;
    return -1;
  }
  return (int)result;
}

int wctomb(char *s, wchar_t wc)
{
  static mbstate_t state;
  if (s == NULL)
  {
    reset_mbstate(&state);
    return 0;
  }

  const size_t result = wcrtomb(s, wc, &state);
  return result == (size_t)-1 ? -1 : (int)result;
}

size_t mbstowcs(wchar_t *dst, const char *src, size_t len)
{
  mbstate_t state = {0};
  const char *input = src;
  return mbsrtowcs(dst, &input, len, &state);
}

size_t wcstombs(char *dst, const wchar_t *src, size_t len)
{
  mbstate_t state = {0};
  const wchar_t *input = src;
  return wcsrtombs(dst, &input, len, &state);
}

#endif
