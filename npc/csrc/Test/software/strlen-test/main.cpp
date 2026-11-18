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

  CHECK(strspn("abcabc", "cba") == 6);
  CHECK(strspn("abc123", "abc") == 3);
  CHECK(strspn("123abc", "abc") == 0);
  CHECK(strspn("", "abc") == 0);
  CHECK(strspn("abc", "") == 0);

  CHECK(strcspn("abc123", "123") == 3);
  CHECK(strcspn("abcabc", "xyz") == 6);
  CHECK(strcspn("123abc", "123") == 0);
  CHECK(strcspn("", "123") == 0);
  CHECK(strcspn("abc", "") == 3);

  const char *search = "kernel/library";
  CHECK(strpbrk(search, "/:") == search + 6);
  CHECK(strpbrk(search, "xz") == NULL);
  CHECK(strpbrk(search, "") == NULL);
  CHECK(strpbrk("", "abc") == NULL);

  const char high_bytes[] = {(char)0x80, (char)0xff, 'A', '\0'};
  const char high_accept[] = {(char)0x80, (char)0xff, '\0'};
  const char high_reject[] = {(char)0xff, '\0'};
  CHECK(strspn(high_bytes, high_accept) == 2);
  CHECK(strcspn(high_bytes, high_reject) == 1);
  CHECK(strpbrk(high_bytes, high_reject) == high_bytes + 1);

  char token_buffer[] = ",alpha,,beta:gamma,";
  char *token_save = (char *)(uintptr_t)1;
  char *token = strtok_r(token_buffer, ",:", &token_save);
  CHECK(token != NULL && strcmp(token, "alpha") == 0);
  token = strtok_r(NULL, ",:", &token_save);
  CHECK(token != NULL && strcmp(token, "beta") == 0);
  token = strtok_r(NULL, ",:", &token_save);
  CHECK(token != NULL && strcmp(token, "gamma") == 0);
  CHECK(strtok_r(NULL, ",:", &token_save) == NULL);
  CHECK(strtok_r(NULL, ",:", &token_save) == NULL);

  char empty_token_source[] = "";
  token_save = (char *)(uintptr_t)1;
  CHECK(strtok_r(empty_token_source, ",", &token_save) == NULL);
  CHECK(token_save == empty_token_source);

  char all_delimiters[] = ",,,";
  token_save = all_delimiters + 1;
  CHECK(strtok_r(all_delimiters, ",", &token_save) == NULL);
  CHECK(strtok_r(NULL, ",", &token_save) == NULL);

  char no_delimiters[] = "whole value";
  token_save = NULL;
  token = strtok_r(no_delimiters, "", &token_save);
  CHECK(token == no_delimiters && strcmp(token, "whole value") == 0);
  CHECK(strtok_r(NULL, "", &token_save) == NULL);

  char changing_delimiters[] = "one,two:three";
  token_save = NULL;
  token = strtok_r(changing_delimiters, ",", &token_save);
  CHECK(token != NULL && strcmp(token, "one") == 0);
  token = strtok_r(NULL, ":", &token_save);
  CHECK(token != NULL && strcmp(token, "two") == 0);
  token = strtok_r(NULL, ":", &token_save);
  CHECK(token != NULL && strcmp(token, "three") == 0);

  char interleaved_left[] = "left,right";
  char interleaved_right[] = "up:down";
  char *left_save = NULL;
  char *right_save = NULL;
  CHECK(strcmp(strtok_r(interleaved_left, ",", &left_save), "left") == 0);
  CHECK(strcmp(strtok_r(interleaved_right, ":", &right_save), "up") == 0);
  CHECK(strcmp(strtok_r(NULL, ",", &left_save), "right") == 0);
  CHECK(strcmp(strtok_r(NULL, ":", &right_save), "down") == 0);

  char standard_tokens[] = ",one,,two:";
  token = strtok(standard_tokens, ",:");
  CHECK(token != NULL && strcmp(token, "one") == 0);
  token = strtok(NULL, ",:");
  CHECK(token != NULL && strcmp(token, "two") == 0);
  CHECK(strtok(NULL, ",:") == NULL);

  char empty_tokens[] = ",alpha,,beta,";
  char *remaining = empty_tokens;
  token = strsep(&remaining, ",");
  CHECK(token != NULL && strcmp(token, "") == 0);
  token = strsep(&remaining, ",");
  CHECK(token != NULL && strcmp(token, "alpha") == 0);
  token = strsep(&remaining, ",");
  CHECK(token != NULL && strcmp(token, "") == 0);
  token = strsep(&remaining, ",");
  CHECK(token != NULL && strcmp(token, "beta") == 0);
  token = strsep(&remaining, ",");
  CHECK(token != NULL && strcmp(token, "") == 0);
  CHECK(remaining == NULL);
  CHECK(strsep(&remaining, ",") == NULL);

  char unsplit[] = "whole";
  remaining = unsplit;
  token = strsep(&remaining, "");
  CHECK(token == unsplit && strcmp(token, "whole") == 0);
  CHECK(remaining == NULL);

  char empty_separated[] = "";
  remaining = empty_separated;
  token = strsep(&remaining, ",");
  CHECK(token == empty_separated && token[0] == '\0');
  CHECK(remaining == NULL);
  CHECK(strsep(&remaining, ",") == NULL);

  CHECK(strcasecmp("Kernel", "kErNeL") == 0);
  CHECK(strcasecmp("Alpha", "beta") < 0);
  CHECK(strcasecmp("gamma", "BETA") > 0);
  CHECK(strcasecmp("abc", "ABCD") < 0);
  CHECK(strcasecmp("ABCD", "abc") > 0);
  CHECK(strcasecmp("", "") == 0);
  CHECK(strncasecmp("Kernel", "KERchief", 3) == 0);
  CHECK(strncasecmp("Kernel", "KERchief", 4) > 0);
  CHECK(strncasecmp("a", "Z", 0) == 0);
  const char high_case_1[] = {(char)0x80, 'A', '\0'};
  const char high_case_2[] = {(char)0x80, 'a', '\0'};
  const char high_case_3[] = {(char)0xff, 'a', '\0'};
  CHECK(strcasecmp(high_case_1, high_case_2) == 0);
  CHECK(strcasecmp(high_case_1, high_case_3) < 0);

  char *duplicate = strdup("kernel string");
  CHECK(duplicate != NULL && strcmp(duplicate, "kernel string") == 0);
  if (duplicate != NULL)
  {
    duplicate[0] = 'K';
    CHECK(strcmp(duplicate, "Kernel string") == 0);
    free(duplicate);
  }
  duplicate = strdup("");
  CHECK(duplicate != NULL && duplicate[0] == '\0');
  free(duplicate);

  duplicate = strndup("abcdef", 3);
  CHECK(duplicate != NULL && strcmp(duplicate, "abc") == 0);
  free(duplicate);
  duplicate = strndup("abc", 99);
  CHECK(duplicate != NULL && strcmp(duplicate, "abc") == 0);
  free(duplicate);
  const char unterminated_duplicate[] = {'x', 'y', 'z'};
  duplicate = strndup(unterminated_duplicate,
                      sizeof(unterminated_duplicate));
  CHECK(duplicate != NULL && strcmp(duplicate, "xyz") == 0);
  free(duplicate);
  duplicate = strndup(unterminated_duplicate, 0);
  CHECK(duplicate != NULL && duplicate[0] == '\0');
  free(duplicate);

  const unsigned char binary_haystack[] = {
      0x10, 0x00, 0x20, 0xff, 0x00, 0x20, 0x30};
  const unsigned char binary_needle[] = {0x00, 0x20};
  const unsigned char missing_needle[] = {0x20, 0x00, 0x31};
  CHECK(memmem(binary_haystack, sizeof(binary_haystack),
               binary_needle, sizeof(binary_needle)) == binary_haystack + 1);
  CHECK(memmem(binary_haystack, sizeof(binary_haystack),
               binary_haystack + 3, 3) == binary_haystack + 3);
  CHECK(memmem(binary_haystack, sizeof(binary_haystack),
               missing_needle, sizeof(missing_needle)) == NULL);
  CHECK(memmem(binary_haystack, 1,
               binary_needle, sizeof(binary_needle)) == NULL);
  CHECK(memmem(binary_haystack, sizeof(binary_haystack),
               binary_needle, 0) == binary_haystack);
  const unsigned char empty_anchor = 0;
  CHECK(memmem(&empty_anchor, 0, &empty_anchor, 0) == &empty_anchor);
  const unsigned char repeated_haystack[] = {'a', 'a', 'a', 'a'};
  const unsigned char repeated_needle[] = {'a', 'a', 'a'};
  CHECK(memmem(repeated_haystack, sizeof(repeated_haystack),
               repeated_needle, sizeof(repeated_needle)) ==
        repeated_haystack);
  CHECK(memmem(repeated_haystack, sizeof(repeated_haystack),
               repeated_haystack, sizeof(repeated_haystack)) ==
        repeated_haystack);

  const unsigned char binary[] = {0x00, 0x11, 0xff, 0x22, 0xff};
  CHECK(memchr(binary, 0xff, sizeof(binary)) == binary + 2);
  CHECK(memchr(binary, 0x1ff, sizeof(binary)) == binary + 2);
  CHECK(memchr(binary, 0x22, sizeof(binary)) == binary + 3);
  CHECK(memchr(binary, 0x33, sizeof(binary)) == NULL);
  CHECK(memchr(binary, 0x00, sizeof(binary)) == binary);
  CHECK(memchr(binary, 0xff, 0) == NULL);

  char move_buf[16] = "abcdef";
  CHECK(memmove(move_buf + 2, move_buf, 6) == move_buf + 2);
  CHECK(strcmp(move_buf, "ababcdef") == 0);
  strcpy(move_buf, "abcdef");
  CHECK(memmove(move_buf, move_buf + 2, 5) == move_buf);
  CHECK(strcmp(move_buf, "cdef") == 0);
  strcpy(move_buf, "abcdef");
  CHECK(memmove(move_buf, move_buf, sizeof(move_buf)) == move_buf);
  CHECK(strcmp(move_buf, "abcdef") == 0);

  char copy_compat[12];
  memset(copy_compat, 'Z', sizeof(copy_compat));
  char *copy_end = stpcpy(copy_compat, "abc");
  CHECK(copy_end == copy_compat + 3);
  CHECK(strcmp(copy_compat, "abc") == 0);
  CHECK(*copy_end == '\0');
  CHECK(copy_compat[4] == 'Z');

  copy_end = stpcpy(copy_compat, "");
  CHECK(copy_end == copy_compat);
  CHECK(copy_compat[0] == '\0');

  const char high_copy_source[] = {(char)0x80, (char)0xff, '\0'};
  copy_end = stpcpy(copy_compat, high_copy_source);
  CHECK(copy_end == copy_compat + 2);
  CHECK(memcmp(copy_compat, high_copy_source,
               sizeof(high_copy_source)) == 0);

  memset(copy_compat, 'Z', sizeof(copy_compat));
  copy_end = stpncpy(copy_compat, "abc", 6);
  CHECK(copy_end == copy_compat + 3);
  CHECK(memcmp(copy_compat, "abc\0\0\0", 6) == 0);
  CHECK(copy_compat[6] == 'Z');

  const char unterminated_copy_source[] = {'w', 'x', 'y', 'z'};
  memset(copy_compat, 'Z', sizeof(copy_compat));
  copy_end = stpncpy(copy_compat, unterminated_copy_source,
                     sizeof(unterminated_copy_source));
  CHECK(copy_end == copy_compat + sizeof(unterminated_copy_source));
  CHECK(memcmp(copy_compat, unterminated_copy_source,
               sizeof(unterminated_copy_source)) == 0);
  CHECK(copy_compat[sizeof(unterminated_copy_source)] == 'Z');

  copy_compat[0] = 'Q';
  copy_end = stpncpy(copy_compat, unterminated_copy_source, 0);
  CHECK(copy_end == copy_compat);
  CHECK(copy_compat[0] == 'Q');

  const unsigned char binary_copy_source[] = {
      0x00, 0x80, 0xff, 0x41, 0x00};
  unsigned char binary_copy_dst[sizeof(binary_copy_source) + 1];
  memset(binary_copy_dst, 0x55, sizeof(binary_copy_dst));
  void *binary_copy_end =
      mempcpy(binary_copy_dst, binary_copy_source,
              sizeof(binary_copy_source));
  CHECK(binary_copy_end == binary_copy_dst + sizeof(binary_copy_source));
  CHECK(memcmp(binary_copy_dst, binary_copy_source,
               sizeof(binary_copy_source)) == 0);
  CHECK(binary_copy_dst[sizeof(binary_copy_source)] == 0x55);

  binary_copy_dst[0] = 0x5a;
  const unsigned char zero_length_source = 0x33;
  binary_copy_end = mempcpy(binary_copy_dst, &zero_length_source, 0);
  CHECK(binary_copy_end == binary_copy_dst);
  CHECK(binary_copy_dst[0] == 0x5a);

  char bounded_cat[16] = "ab";
  CHECK(strncat(bounded_cat, "cdef", 2) == bounded_cat);
  CHECK(strcmp(bounded_cat, "abcd") == 0);
  CHECK(strncat(bounded_cat, "", 4) == bounded_cat);
  CHECK(strcmp(bounded_cat, "abcd") == 0);

  const char unterminated_cat_source[] = {
      (char)0x80, (char)0xff, 'X'};
  CHECK(strncat(bounded_cat, unterminated_cat_source,
                sizeof(unterminated_cat_source)) == bounded_cat);
  const unsigned char expected_bounded_cat[] = {
      'a', 'b', 'c', 'd', 0x80, 0xff, 'X', '\0'};
  CHECK(memcmp(bounded_cat, expected_bounded_cat,
               sizeof(expected_bounded_cat)) == 0);

  char no_append[4] = "xy";
  CHECK(strncat(no_append, "ignored", 0) == no_append);
  CHECK(strcmp(no_append, "xy") == 0);

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
