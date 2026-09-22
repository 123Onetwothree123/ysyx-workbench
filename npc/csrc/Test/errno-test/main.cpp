#include <am.h>
#include <errno.h>
#include <klib.h>
#include <limits.h>

#if !defined(__ISA_NATIVE__)
static int failures;

#define CHECK(condition)                                                     \
  do {                                                                       \
    if (!(condition)) {                                                      \
      failures++;                                                            \
      printf("errno-test check failed at line %d\n", __LINE__);             \
    }                                                                        \
  } while (0)

static void test_known_errors(void) {
  char exact[17];
  char truncated[4] = {'X', 'X', 'X', 'X'};
  char one[1] = {'X'};
  char untouched = 'Q';

  errno = EFAULT;
  CHECK(strcmp(strerror(0), "Success") == 0);
  CHECK(strcmp(strerror(EINVAL), "Invalid argument") == 0);
  CHECK(strcmp(strerror(ENOENT), "No such file or directory") == 0);
  CHECK(strcmp(strerror(ENOMEM), "Cannot allocate memory") == 0);
  CHECK(strcmp(strerror(ECONNREFUSED), "Connection refused") == 0);
  CHECK(strcmp(strerror(EOWNERDEAD), "Owner died") == 0);
  CHECK(errno == EFAULT);

  CHECK(strerror_r(EINVAL, exact, sizeof(exact)) == 0);
  CHECK(strcmp(exact, "Invalid argument") == 0);
  CHECK(errno == EFAULT);

  CHECK(strerror_r(EINVAL, truncated, sizeof(truncated)) == ERANGE);
  CHECK(truncated[0] == 'I');
  CHECK(truncated[1] == 'n');
  CHECK(truncated[2] == 'v');
  CHECK(truncated[3] == '\0');

  CHECK(strerror_r(EINVAL, one, sizeof(one)) == ERANGE);
  CHECK(one[0] == '\0');

  CHECK(strerror_r(EINVAL, &untouched, 0) == ERANGE);
  CHECK(untouched == 'Q');
  CHECK(strerror_r(EINVAL, NULL, 16) == EINVAL);
}

static void test_unknown_errors(void) {
  char buffer[32];
  char truncated[8];

  errno = EFAULT;
  CHECK(strcmp(strerror(123456), "Unknown error 123456") == 0);
  CHECK(strcmp(strerror(-17), "Unknown error -17") == 0);
  CHECK(strcmp(strerror(INT_MIN), "Unknown error -2147483648") == 0);
  CHECK(errno == EFAULT);

  CHECK(strerror_r(123456, buffer, sizeof(buffer)) == EINVAL);
  CHECK(strcmp(buffer, "Unknown error 123456") == 0);
  CHECK(errno == EFAULT);

  CHECK(strerror_r(-17, buffer, sizeof(buffer)) == EINVAL);
  CHECK(strcmp(buffer, "Unknown error -17") == 0);

  CHECK(strerror_r(123456, truncated, sizeof(truncated)) == ERANGE);
  CHECK(truncated[sizeof(truncated) - 1] == '\0');
  CHECK(strncmp(truncated, "Unknown", sizeof(truncated) - 1) == 0);
}

static void test_errno_aliases(void) {
  CHECK(EWOULDBLOCK == EAGAIN);
  CHECK(EDEADLOCK == EDEADLK);
  CHECK(ENOTSUP == EOPNOTSUPP);
  CHECK(strcmp(strerror(EWOULDBLOCK), "Resource temporarily unavailable") == 0);
  CHECK(strcmp(strerror(ENOTSUP), "Operation not supported") == 0);
}
#endif

int main() {
#if defined(__ISA_NATIVE__)
  printf("errno-test skipped: native libc is active\n");
  return 0;
#else
  test_known_errors();
  test_unknown_errors();
  test_errno_aliases();

  printf(failures == 0 ? "ERRNO TEST PASS\n"
                       : "ERRNO TEST FAIL, failures=%d\n",
         failures);
  halt(failures == 0 ? 0 : 1);
  return failures == 0 ? 0 : 1;
#endif
}
