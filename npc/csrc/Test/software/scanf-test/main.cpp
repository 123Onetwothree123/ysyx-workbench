#include <am.h>
#include <errno.h>
#include <klib.h>

#include <limits.h>
#include <stdint.h>

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
#include "stdio_impl.h"

static int failures;

#define CHECK(condition)                                                     \
  do                                                                         \
  {                                                                          \
    if (!(condition))                                                        \
    {                                                                        \
      failures++;                                                            \
      printf("scanf-test check failed at line %d\n", __LINE__);            \
    }                                                                        \
  } while (0)

static int call_vsscanf(const char *input, const char *format, ...)
{
  va_list ap;
  va_start(ap, format);
  int result = vsscanf(input, format, ap);
  va_end(ap);
  return result;
}

static int call_vfscanf(FILE *stream, const char *format, ...)
{
  va_list ap;
  va_start(ap, format);
  int result = vfscanf(stream, format, ap);
  va_end(ap);
  return result;
}

static int call_vscanf(const char *format, ...)
{
  va_list ap;
  va_start(ap, format);
  int result = vscanf(format, ap);
  va_end(ap);
  return result;
}

typedef struct
{
  const unsigned char *data;
  size_t length;
  size_t position;
} ScanReadCookie;

static size_t scan_memory_read(
    FILE *stream,
    unsigned char *destination,
    size_t length)
{
  ScanReadCookie *cookie = (ScanReadCookie *)stream->cookie;
  size_t remaining = cookie->length - cookie->position;
  size_t amount = length < remaining ? length : remaining;
  if (amount != 0)
  {
    memcpy(destination, cookie->data + cookie->position, amount);
    cookie->position += amount;
  }
  return amount;
}

static void init_scan_stream(
    FILE *stream,
    ScanReadCookie *cookie,
    unsigned char *storage,
    size_t storage_size,
    const char *text)
{
  CHECK(storage_size != 0);
  *cookie = (ScanReadCookie){
      .data = (const unsigned char *)text,
      .length = strlen(text),
      .position = 0,
  };
  *stream = (FILE){
      .flags = F_NOWR,
      .rpos = storage,
      .rend = storage,
      .buf = storage,
      .buf_size = storage_size,
      .read = scan_memory_read,
      .cookie = cookie,
      .lbf = EOF,
      .lock = -1,
  };
}

static void test_integer_conversions(void)
{
  int decimal = 0;
  int octal_i = 0;
  int hex_i = 0;
  unsigned int decimal_u = 0;
  unsigned int hex = 0;
  unsigned int octal = 0;
  int result = sscanf(" -42 077 0x2a 99 DeAd 17",
                      "%d %i %i %u %X %o",
                      &decimal, &octal_i, &hex_i, &decimal_u, &hex, &octal);
  CHECK(result == 6);
  CHECK(decimal == -42);
  CHECK(octal_i == 63);
  CHECK(hex_i == 42);
  CHECK(decimal_u == 99u);
  CHECK(hex == 0xdeadu);
  CHECK(octal == 15u);

  int auto_value = -1;
  char tail = '?';
  result = sscanf("09", "%i%c", &auto_value, &tail);
  CHECK(result == 2);
  CHECK(auto_value == 0);
  CHECK(tail == '9');

  unsigned int short_hex = 99;
  tail = '?';
  result = sscanf("0x2a", "%3x%c", &short_hex, &tail);
  CHECK(result == 2);
  CHECK(short_hex == 2u);
  CHECK(tail == 'a');

  short_hex = 99;
  tail = '?';
  result = sscanf("0x2a", "%2x%c", &short_hex, &tail);
  CHECK(result == 0);
  CHECK(short_hex == 99u);
  CHECK(tail == '?');

  short_hex = 99;
  tail = '?';
  result = sscanf("0x2a", "%1x%c", &short_hex, &tail);
  CHECK(result == 2);
  CHECK(short_hex == 0u);
  CHECK(tail == 'x');

  auto_value = 99;
  tail = '?';
  result = sscanf("0xZ", "%i%c", &auto_value, &tail);
  CHECK(result == 0);
  CHECK(auto_value == 99);
  CHECK(tail == '?');

  decimal_u = 0;
  errno = EFAULT;
  result = sscanf("-1", "%u", &decimal_u);
  CHECK(result == 1);
  CHECK(decimal_u == UINT_MAX);
  CHECK(errno == EFAULT);

  decimal_u = 0;
  errno = EFAULT;
  result = sscanf("-2", "%u", &decimal_u);
  CHECK(result == 1);
  CHECK(decimal_u == UINT_MAX - 1u);
  CHECK(errno == EFAULT);

  decimal = 99;
  tail = '?';
  result = sscanf("-123", "%2d%c", &decimal, &tail);
  CHECK(result == 2);
  CHECK(decimal == -1);
  CHECK(tail == '2');
}

static void test_width_suppression_and_literals(void)
{
  int value = -1;
  char word[8] = "bad";
  char tail = '?';
  int result = sscanf("12345 abc!", "%*3d%2d %3s%c",
                      &value, word, &tail);
  CHECK(result == 3);
  CHECK(value == 45);
  CHECK(strcmp(word, "abc") == 0);
  CHECK(tail == '!');

  int left = 0;
  int right = 0;
  result = sscanf("id=12%/34", "id=%d%%/%d", &left, &right);
  CHECK(result == 2);
  CHECK(left == 12);
  CHECK(right == 34);

  left = -1;
  right = 777;
  result = sscanf("12x34", "%dX%d", &left, &right);
  CHECK(result == 1);
  CHECK(left == 12);
  CHECK(right == 777);

  value = -1;
  result = sscanf("11 22", "%*d%d", &value);
  CHECK(result == 1);
  CHECK(value == 22);

  value = -1;
  CHECK(call_vsscanf("  321", " %d", &value) == 1);
  CHECK(value == 321);
}

static void test_strings_and_characters(void)
{
  char first[8] = "bad";
  char second[8] = "bad";
  int result = sscanf("   alpha beta", "%5s %s", first, second);
  CHECK(result == 2);
  CHECK(strcmp(first, "alpha") == 0);
  CHECK(strcmp(second, "beta") == 0);

  char chars[4] = {'X', 'X', 'X', 'X'};
  result = sscanf(" abcd", "%3c", chars);
  CHECK(result == 1);
  CHECK(chars[0] == ' ');
  CHECK(chars[1] == 'a');
  CHECK(chars[2] == 'b');
  CHECK(chars[3] == 'X');

  char last = '?';
  result = sscanf("abcdef", "%*5c%c", &last);
  CHECK(result == 1);
  CHECK(last == 'f');

  chars[0] = 'X';
  chars[1] = 'Y';
  result = sscanf("a", "%2c", chars);
  CHECK(result == EOF);
  /* As with a hosted scanf, an input failure may leave a partial %c item. */
  CHECK(chars[0] == 'a');
  CHECK(chars[1] == 'Y');
}

static void test_length_modifiers(void)
{
  signed char hh = 0;
  short h = 0;
  long l = 0;
  long long ll = 0;
  ptrdiff_t z = 0;
  ptrdiff_t t = 0;
  intmax_t j = 0;
  unsigned char uhh = 0;
  unsigned short uh = 0;
  unsigned long ul = 0;
  unsigned long long ull = 0;
  size_t uz = 0;
  size_t ut = 0;
  uintmax_t uj = 0;

  int result = sscanf(
      "-12 -1234 -1234567 -5000000000 -77 -88 -9000000000 "
      "250 60000 3000000000 12000000000 12345 54321 18000000000",
      "%hhd %hd %ld %lld %zd %td %jd "
      "%hhu %hu %lu %llu %zu %tu %ju",
      &hh, &h, &l, &ll, &z, &t, &j,
      &uhh, &uh, &ul, &ull, &uz, &ut, &uj);
  CHECK(result == 14);
  CHECK(hh == -12);
  CHECK(h == -1234);
  CHECK(l == -1234567L);
  CHECK(ll == -5000000000LL);
  CHECK(z == (ptrdiff_t)-77);
  CHECK(t == (ptrdiff_t)-88);
  CHECK(j == (intmax_t)-9000000000LL);
  CHECK(uhh == 250u);
  CHECK(uh == 60000u);
  CHECK(ul == 3000000000UL);
  CHECK(ull == 12000000000ULL);
  CHECK(uz == (size_t)12345);
  CHECK(ut == (size_t)54321);
  CHECK(uj == (uintmax_t)18000000000ULL);
}

static void test_pointer(void)
{
  void *pointer = NULL;
  char tail = '?';
  int result = sscanf("0x1234Z", "%p%c", &pointer, &tail);
  CHECK(result == 2);
  CHECK((uintptr_t)pointer == (uintptr_t)0x1234);
  CHECK(tail == 'Z');

  pointer = (void *)(uintptr_t)1;
  result = sscanf("0", "%p", &pointer);
  CHECK(result == 1);
  CHECK(pointer == NULL);
}

static void test_failures_and_eof(void)
{
  int value = 123;
  char ch = 'Q';
  float floating = 1.0f;
  uint32_t floating_bits_before;
  uint32_t floating_bits_after;
  char scan_set[4] = "XYZ";
  wchar_t wide_string[4] = {L'X', L'Y', L'Z', L'\0'};
  memcpy(&floating_bits_before, &floating, sizeof(floating_bits_before));
  CHECK(sscanf("", "%d", &value) == EOF);
  CHECK(value == 123);
  CHECK(sscanf("   ", "%d", &value) == EOF);
  CHECK(sscanf("x", "%d", &value) == 0);
  CHECK(sscanf("", "%c", &ch) == EOF);
  CHECK(ch == 'Q');
  CHECK(sscanf("", "x") == EOF);
  CHECK(sscanf("y", "x") == 0);
  CHECK(sscanf("", "   ") == 0);
  CHECK(sscanf("", "%%") == EOF);
  CHECK(sscanf("", "%*d") == EOF);
  CHECK(sscanf("7", "%*d%d", &value) == EOF);

  int first = 0;
  int second = 456;
  CHECK(sscanf("123", "%d%d", &first, &second) == 1);
  CHECK(first == 123);
  CHECK(second == 456);

  errno = 0;
  CHECK(sscanf("1.5", "%f", &floating) == 0);
  CHECK(errno == EINVAL);
  memcpy(&floating_bits_after, &floating, sizeof(floating_bits_after));
  CHECK(floating_bits_after == floating_bits_before);

  errno = 0;
  CHECK(sscanf("7", "%n", &value) == 0);
  CHECK(errno == EINVAL);
  CHECK(value == 123);

  errno = 0;
  CHECK(sscanf("abc", "%[a]", scan_set) == 0);
  CHECK(errno == EINVAL);
  CHECK(strcmp(scan_set, "XYZ") == 0);

  errno = 0;
  CHECK(sscanf("7", "%0d", &value) == 0);
  CHECK(errno == EINVAL);

  errno = 0;
  CHECK(sscanf("abc", "%ls", wide_string) == 0);
  CHECK(errno == EINVAL);
  CHECK(wide_string[0] == L'X');

  errno = 0;
  value = 0;
  CHECK(sscanf("7", "%d%q", &value) == 1);
  CHECK(value == 7);
  CHECK(errno == EINVAL);
}

static void test_overflow(void)
{
  int signed_value = 0;
  errno = 0;
  CHECK(sscanf("999999999999999999999999999", "%d", &signed_value) == 1);
  CHECK(signed_value == INT_MAX);
  CHECK(errno == ERANGE);

  errno = 0;
  CHECK(sscanf("-999999999999999999999999999", "%d", &signed_value) == 1);
  CHECK(signed_value == INT_MIN);
  CHECK(errno == ERANGE);

  unsigned char byte = 0;
  errno = 0;
  CHECK(sscanf("999", "%hhu", &byte) == 1);
  CHECK(byte == UCHAR_MAX);
  CHECK(errno == ERANGE);

  uintmax_t maximum = 0;
  char tail = '?';
  errno = 0;
  CHECK(sscanf("999999999999999999999999999Z", "%ju%c",
               &maximum, &tail) == 2);
  CHECK(maximum == UINTMAX_MAX);
  CHECK(tail == 'Z');
  CHECK(errno == ERANGE);

  errno = EFAULT;
  unsigned int normal = 0;
  CHECK(sscanf("42", "%u", &normal) == 1);
  CHECK(normal == 42u);
  CHECK(errno == EFAULT);
}

static void test_stream_scanning(void)
{
  unsigned char storage[24];
  ScanReadCookie cookie;
  FILE stream;
  int value = -1;

  init_scan_stream(&stream, &cookie, storage, sizeof(storage), "123Z");
  CHECK(fscanf(&stream, "%d", &value) == 1);
  CHECK(value == 123);
  CHECK(fgetc(&stream) == 'Z');

  init_scan_stream(&stream, &cookie, storage, sizeof(storage), "Q42");
  value = -1;
  CHECK(fscanf(&stream, "X%d", &value) == 0);
  CHECK(value == -1);
  CHECK(fgetc(&stream) == 'Q');
  CHECK(fscanf(&stream, "%d", &value) == 1);
  CHECK(value == 42);

  init_scan_stream(&stream, &cookie, storage, sizeof(storage), "X");
  CHECK(fscanf(&stream, "%%") == 0);
  CHECK(fgetc(&stream) == 'X');

  init_scan_stream(&stream, &cookie, storage, sizeof(storage), "   X");
  CHECK(fscanf(&stream, " ") == 0);
  CHECK(fgetc(&stream) == 'X');

  init_scan_stream(&stream, &cookie, storage, sizeof(storage), "Z");
  value = -1;
  CHECK(fscanf(&stream, "%d", &value) == 0);
  CHECK(value == -1);
  CHECK(feof(&stream) == 0);
  CHECK(fgetc(&stream) == 'Z');

  unsigned int hex = 99;
  init_scan_stream(&stream, &cookie, storage, sizeof(storage), "0xZ");
  CHECK(fscanf(&stream, "%x", &hex) == 0);
  CHECK(hex == 99u);
  /* 0x is the failed input item; Z is the first nonmatching character. */
  CHECK(fgetc(&stream) == 'Z');

  char word[8] = "bad";
  init_scan_stream(&stream, &cookie, storage, sizeof(storage), "alpha beta");
  CHECK(fscanf(&stream, "%s", word) == 1);
  CHECK(strcmp(word, "alpha") == 0);
  CHECK(fgetc(&stream) == ' ');
  CHECK(fscanf(&stream, "%4s", word) == 1);
  CHECK(strcmp(word, "beta") == 0);

  init_scan_stream(&stream, &cookie, storage, sizeof(storage), "-17!");
  value = 0;
  CHECK(call_vfscanf(&stream, "%d", &value) == 1);
  CHECK(value == -17);
  CHECK(fgetc(&stream) == '!');

  init_scan_stream(&stream, &cookie, storage, sizeof(storage), "12Z");
  CHECK(fscanf(&stream, "%*d") == 0);
  CHECK(fgetc(&stream) == 'Z');

  init_scan_stream(&stream, &cookie, storage, sizeof(storage), "12");
  value = 9;
  CHECK(fscanf(&stream, "%*d%d", &value) == EOF);
  CHECK(value == 9);

  char chars[2] = {'X', 'Y'};
  init_scan_stream(&stream, &cookie, storage, sizeof(storage), "A");
  CHECK(fscanf(&stream, "%2c", chars) == EOF);
  CHECK(chars[0] == 'A');
  CHECK(chars[1] == 'Y');
  CHECK(feof(&stream) != 0);

  init_scan_stream(&stream, &cookie, storage, sizeof(storage), "");
  value = 7;
  CHECK(fscanf(&stream, "%d", &value) == EOF);
  CHECK(value == 7);
  CHECK(feof(&stream) != 0);

  clearerr(stdin);
  value = 7;
  CHECK(scanf("%d", &value) == EOF);
  CHECK(value == 7);
  CHECK(feof(stdin) != 0);
  clearerr(stdin);
  CHECK(call_vscanf("%d", &value) == EOF);
  CHECK(value == 7);
  CHECK(feof(stdin) != 0);
  clearerr(stdin);
}

#endif

int main()
{
#if defined(__ISA_NATIVE__) && !defined(__NATIVE_USE_KLIB__)
  printf("scanf-test skipped: native libc is active\n");
  return 0;
#else
  test_integer_conversions();
  test_width_suppression_and_literals();
  test_strings_and_characters();
  test_length_modifiers();
  test_pointer();
  test_failures_and_eof();
  test_overflow();
  test_stream_scanning();

  printf(failures == 0 ? "SCANF TEST PASS\n"
                       : "SCANF TEST FAIL, failures=%d\n",
         failures);
  halt(failures == 0 ? 0 : 1);
  return failures == 0 ? 0 : 1;
#endif
}
