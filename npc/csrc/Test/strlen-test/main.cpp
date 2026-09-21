#include <am.h>
#include <klib.h>
#include <errno.h>
#include <limits.h>

static int failures;

#define CHECK(cond) \
  do { \
    if (!(cond)) { \
      failures++; \
      printf("string check failed at line %d\n", __LINE__); \
    } \
  } while (0)

int main() {
  const char *s = "abcabc";
  CHECK(strlen(s) == 6);
  CHECK(strnlen(s, 10) == 6);
  CHECK(strnlen(s, 3) == 3);
  CHECK(strnlen(s, 0) == 0);

  const struct {
    char unterminated[4];
    char guard;
    char terminator;
  } bounded = {{'a', 'b', 'c', 'd'}, 'X', '\0'};
  CHECK(strnlen(bounded.unterminated, sizeof(bounded.unterminated)) ==
        sizeof(bounded.unterminated));

  CHECK(strchr(s, 'a') == s);
  CHECK(strchr(s, 'b') == s + 1);
  CHECK(strchr(s, 'z') == NULL);
  CHECK(strchr(s, '\0') == s + 6);
  CHECK(strchr(s, 0x100 + 'b') == s + 1);

  CHECK(strrchr(s, 'a') == s + 3);
  CHECK(strrchr(s, 'b') == s + 4);
  CHECK(strrchr(s, 'z') == NULL);
  CHECK(strrchr(s, '\0') == s + 6);
  CHECK(strrchr(s, 0x100 + 'b') == s + 4);

  CHECK(strstr(s, "abc") == s);
  CHECK(strstr(s, "bca") == s + 1);
  CHECK(strstr(s, "cab") == s + 2);
  CHECK(strstr(s, "abcabc") == s);
  CHECK(strstr(s, "abcd") == NULL);
  CHECK(strstr(s, "") == s);
  const char *empty = "";
  const char *repeated = "aaaaa";
  CHECK(strstr(empty, "") == empty);
  CHECK(strstr(empty, "a") == NULL);
  CHECK(strstr(repeated, "aaa") == repeated);

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
  char dst[8];
  memset(dst, 'Z', sizeof(dst));
  ptrdiff_t sc_ret = strscpy(dst, "abc", sizeof(dst));
  CHECK(sc_ret == 3);
  CHECK(strcmp(dst, "abc") == 0);
  CHECK(dst[4] == 'Z');

  sc_ret = strscpy(dst, "1234567", sizeof(dst));
  CHECK(sc_ret == 7);
  CHECK(strcmp(dst, "1234567") == 0);

  sc_ret = strscpy(dst, "123456789", sizeof(dst));
  CHECK(sc_ret == -E2BIG);
  CHECK(strcmp(dst, "1234567") == 0);

  char one = 'Z';
  sc_ret = strscpy(&one, "x", 1);
  CHECK(sc_ret == -E2BIG);
  CHECK(one == '\0');
  one = 'Z';
  sc_ret = strscpy(&one, "", 1);
  CHECK(sc_ret == 0);
  CHECK(one == '\0');
  one = 'Z';
  sc_ret = strscpy(&one, "x", 0);
  CHECK(sc_ret == -E2BIG);
  CHECK(one == 'Z');
  one = 'Z';
  sc_ret = strscpy(&one, "", (size_t)INT_MAX + 1);
  CHECK(sc_ret == -E2BIG);
  CHECK(one == 'Z');

  const struct {
    char source[4];
    char guard;
  } bounded_source = {{'a', 'b', 'c', 'd'}, 'X'};
  char bounded_dst[4];
  sc_ret = strscpy(bounded_dst, bounded_source.source, sizeof(bounded_dst));
  CHECK(sc_ret == -E2BIG);
  CHECK(strcmp(bounded_dst, "abc") == 0);

  memset(dst, 'Z', sizeof(dst));
  size_t sl_ret = strlcpy(dst, "abc", sizeof(dst));
  CHECK(sl_ret == 3);
  CHECK(strcmp(dst, "abc") == 0);
  CHECK(dst[4] == 'Z');

  sl_ret = strlcpy(dst, "123456789", sizeof(dst));
  CHECK(sl_ret == 9);
  CHECK(strcmp(dst, "1234567") == 0);

  one = 'Z';
  sl_ret = strlcpy(&one, "xy", 0);
  CHECK(sl_ret == 2);
  CHECK(one == 'Z');
  one = 'Z';
  sl_ret = strlcpy(&one, "xy", 1);
  CHECK(sl_ret == 2);
  CHECK(one == '\0');

  char cat[8];
  memset(cat, 'Z', sizeof(cat));
  cat[0] = 'a';
  cat[1] = 'b';
  cat[2] = '\0';
  sl_ret = strlcat(cat, "cd", sizeof(cat));
  CHECK(sl_ret == 4);
  CHECK(strcmp(cat, "abcd") == 0);
  CHECK(cat[5] == 'Z');

  strcpy(cat, "abc");
  sl_ret = strlcat(cat, "defg", sizeof(cat));
  CHECK(sl_ret == 7);
  CHECK(strcmp(cat, "abcdefg") == 0);

  strcpy(cat, "ab");
  sl_ret = strlcat(cat, "cdefgh", sizeof(cat));
  CHECK(sl_ret == 8);
  CHECK(strcmp(cat, "abcdefg") == 0);

  char unterminated_dst[4] = {'a', 'b', 'c', 'd'};
  sl_ret = strlcat(unterminated_dst, "xy", sizeof(unterminated_dst));
  CHECK(sl_ret == 6);
  CHECK(memcmp(unterminated_dst, "abcd", sizeof(unterminated_dst)) == 0);

  one = 'Z';
  sl_ret = strlcat(&one, "xy", 0);
  CHECK(sl_ret == 2);
  CHECK(one == 'Z');
  one = '\0';
  sl_ret = strlcat(&one, "xy", 1);
  CHECK(sl_ret == 2);
  CHECK(one == '\0');
#endif

  printf(failures == 0 ? "STRING SEARCH TEST PASS\n"
                       : "STRING SEARCH TEST FAIL, failures=%d\n",
         failures);
  halt(failures == 0 ? 0 : 1);
  return failures == 0 ? 0 : 1;
}
