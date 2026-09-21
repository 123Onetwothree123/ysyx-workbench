#include <am.h>
#include <klib.h>

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

  printf(failures == 0 ? "STRING SEARCH TEST PASS\n"
                       : "STRING SEARCH TEST FAIL, failures=%d\n",
         failures);
  halt(failures == 0 ? 0 : 1);
  return failures == 0 ? 0 : 1;
}
