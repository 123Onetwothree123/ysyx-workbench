#include <am.h>
#include <klib.h>
#include <string.h>
int main() {
  const char *s = "abcdef";
  size_t n = strlen(s);
  printf("strlen=%d (want 6)\n", (int)n);
  printf(n == 6 ? "STRLEN PASS\n" : "STRLEN FAIL\n");
  return n == 6 ? 0 : 1;
}
