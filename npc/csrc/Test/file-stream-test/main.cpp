#include <am.h>
#include <errno.h>
#include <klib.h>
#include <stdint.h>

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
#include "stdio_impl.h"

static int failures;
static int failed_lines[64];
static size_t failed_line_count;

typedef struct {
  unsigned char data[64];
  size_t length;
  size_t calls;
  size_t limit;
} WriteCookie;

typedef struct {
  const unsigned char *data;
  size_t length;
  size_t position;
  size_t calls;
} ReadCookie;

typedef struct {
  unsigned char data[128];
  size_t length;
  size_t position;
  int opens;
  int closes;
} VirtualFile;

static VirtualFile virtual_file;

static void record_check(int condition, int line) {
  if (condition) {
    return;
  }

  failures++;
  if (failed_line_count < sizeof(failed_lines) / sizeof(failed_lines[0])) {
    failed_lines[failed_line_count++] = line;
  }
}

#define CHECK(condition) record_check((condition), __LINE__)

static size_t capped_write(FILE *stream, const unsigned char *data,
                           size_t length) {
  WriteCookie *cookie = (WriteCookie *)stream->cookie;
  size_t amount = length;
  if (amount > cookie->limit) {
    amount = cookie->limit;
  }
  if (amount > sizeof(cookie->data) - cookie->length) {
    amount = sizeof(cookie->data) - cookie->length;
  }
  if (amount != 0) {
    memcpy(cookie->data + cookie->length, data, amount);
    cookie->length += amount;
  }
  cookie->calls++;
  return amount;
}

static size_t capped_repeat(FILE *stream, unsigned char byte, size_t length) {
  WriteCookie *cookie = (WriteCookie *)stream->cookie;
  size_t amount = length;
  if (amount > cookie->limit) {
    amount = cookie->limit;
  }
  if (amount > sizeof(cookie->data) - cookie->length) {
    amount = sizeof(cookie->data) - cookie->length;
  }
  if (amount != 0) {
    memset(cookie->data + cookie->length, byte, amount);
    cookie->length += amount;
  }
  cookie->calls++;
  return amount;
}

static size_t memory_read(FILE *stream, unsigned char *data, size_t length) {
  ReadCookie *cookie = (ReadCookie *)stream->cookie;
  size_t remaining = cookie->length - cookie->position;
  size_t amount = length < remaining ? length : remaining;
  if (amount != 0) {
    memcpy(data, cookie->data + cookie->position, amount);
    cookie->position += amount;
  }
  cookie->calls++;
  return amount;
}

static int virtual_open(const char *path, const char *mode) {
  if (strcmp(path, "memory") != 0) {
    errno = ENOENT;
    return -1;
  }
  virtual_file.opens++;
  virtual_file.position = 0;
  if (mode[0] == 'w') {
    virtual_file.length = 0;
  }
  return 7;
}

static ptrdiff_t virtual_read(int fd, void *buf, size_t len) {
  if (fd != 7) {
    errno = EBADF;
    return -1;
  }
  size_t remaining = virtual_file.length - virtual_file.position;
  size_t amount = len < remaining ? len : remaining;
  memcpy(buf, virtual_file.data + virtual_file.position, amount);
  virtual_file.position += amount;
  return (ptrdiff_t)amount;
}

static ptrdiff_t virtual_write(int fd, const void *buf, size_t len) {
  if (fd != 7) {
    errno = EBADF;
    return -1;
  }
  size_t available = sizeof(virtual_file.data) - virtual_file.position;
  size_t amount = len < available ? len : available;
  memcpy(virtual_file.data + virtual_file.position, buf, amount);
  virtual_file.position += amount;
  if (virtual_file.position > virtual_file.length) {
    virtual_file.length = virtual_file.position;
  }
  return (ptrdiff_t)amount;
}

static int64_t virtual_seek(int fd, int64_t offset, int whence) {
  if (fd != 7) {
    errno = EBADF;
    return -1;
  }
  int64_t base;
  if (whence == SEEK_SET) {
    base = 0;
  } else if (whence == SEEK_CUR) {
    base = (int64_t)virtual_file.position;
  } else if (whence == SEEK_END) {
    base = (int64_t)virtual_file.length;
  } else {
    errno = EINVAL;
    return -1;
  }
  if (offset < -base || offset > (int64_t)sizeof(virtual_file.data) - base) {
    errno = EINVAL;
    return -1;
  }
  virtual_file.position = (size_t)(base + offset);
  return (int64_t)virtual_file.position;
}

static int virtual_close(int fd) {
  if (fd != 7) {
    errno = EBADF;
    return -1;
  }
  virtual_file.closes++;
  return 0;
}

static void test_short_write_backend(void) {
  static const unsigned char payload[] = "abcdefg";
  WriteCookie cookie = {.limit = 2};
  FILE stream = {
      .flags = F_NORD,
      .write = capped_write,
      .cookie = &cookie,
      .lbf = EOF,
      .lock = -1,
  };

  CHECK(fwrite(payload, 1, sizeof(payload) - 1, &stream) ==
        sizeof(payload) - 1);
  CHECK(cookie.calls == 4);
  CHECK(cookie.length == sizeof(payload) - 1);
  CHECK(memcmp(cookie.data, payload, sizeof(payload) - 1) == 0);
  CHECK(ferror(&stream) == 0);
}

static void test_short_repeat_backend(void) {
  WriteCookie cookie = {.limit = 3};
  FILE stream = {
      .flags = F_NORD,
      .write = capped_write,
      .repeat = capped_repeat,
      .cookie = &cookie,
      .lbf = EOF,
      .lock = -1,
  };

  CHECK(__kfile_repeat(&stream, 'R', 8) == 8);
  CHECK(cookie.calls == 3);
  CHECK(cookie.length == 8);
  for (size_t i = 0; i < cookie.length; i++) {
    CHECK(cookie.data[i] == 'R');
  }
  CHECK(ferror(&stream) == 0);
}

static void test_line_buffer(void) {
  static const unsigned char payload[] = "ab\ncd";
  unsigned char buffer[8];
  WriteCookie cookie = {.limit = 2};
  FILE stream = {
      .flags = F_NORD,
      .wbase = buffer,
      .wpos = buffer,
      .wend = buffer + sizeof(buffer),
      .buf = buffer,
      .buf_size = sizeof(buffer),
      .write = capped_write,
      .cookie = &cookie,
      .lbf = '\n',
      .lock = -1,
  };

  CHECK(fwrite(payload, 1, sizeof(payload) - 1, &stream) ==
        sizeof(payload) - 1);
  CHECK(cookie.calls == 2);
  CHECK(cookie.length == 3);
  CHECK(memcmp(cookie.data, "ab\n", 3) == 0);
  CHECK((size_t)(stream.wpos - stream.wbase) == 2);
  CHECK(memcmp(stream.wbase, "cd", 2) == 0);

  CHECK(fflush(&stream) == 0);
  CHECK(cookie.calls == 3);
  CHECK(cookie.length == sizeof(payload) - 1);
  CHECK(memcmp(cookie.data, payload, sizeof(payload) - 1) == 0);
  CHECK(stream.wpos == stream.wbase);
  CHECK(ferror(&stream) == 0);
}

static void test_memory_read_backend(void) {
  static const unsigned char input[] = "abc\ndef\n";
  unsigned char storage[4];
  unsigned char first[2];
  char line[8];
  ReadCookie cookie = {
      .data = input,
      .length = sizeof(input) - 1,
  };
  FILE stream = {
      .flags = F_NOWR,
      .rpos = storage,
      .rend = storage,
      .buf = storage,
      .buf_size = 4,
      .read = memory_read,
      .cookie = &cookie,
      .lbf = EOF,
      .lock = -1,
  };

  CHECK(fread(first, 1, sizeof(first), &stream) == sizeof(first));
  CHECK(first[0] == 'a' && first[1] == 'b');
  CHECK(ungetc('Q', &stream) == 'Q');
  CHECK(fgetc(&stream) == 'Q');

  CHECK(fgets(line, sizeof(line), &stream) == line);
  CHECK(strcmp(line, "c\n") == 0);
  CHECK(fgets(line, sizeof(line), &stream) == line);
  CHECK(strcmp(line, "def\n") == 0);
  CHECK(fgetc(&stream) == EOF);
  CHECK(feof(&stream) != 0);
  CHECK(ferror(&stream) == 0);
  CHECK(cookie.position == cookie.length);
  CHECK(cookie.calls >= 2);
}

static void test_close_unseekable_input(void) {
  static const unsigned char input[] = "pipe";
  unsigned char storage[8];
  ReadCookie cookie = {
      .data = input,
      .length = sizeof(input) - 1,
  };
  FILE stream = {
      .flags = F_NOWR,
      .rpos = storage,
      .rend = storage,
      .buf = storage,
      .buf_size = sizeof(storage),
      .read = memory_read,
      .cookie = &cookie,
      .lbf = EOF,
      .lock = -1,
  };

  CHECK(fgetc(&stream) == 'p');
  CHECK(cookie.position == cookie.length);
  errno = EFAULT;
  CHECK(fclose(&stream) == 0);
  CHECK(errno == EFAULT);
}

static void test_buffer_configuration(void) {
  unsigned char user_buffer[8];
  WriteCookie cookie = {.limit = sizeof(cookie.data)};
  FILE stream = {
      .flags = F_NORD,
      .write = capped_write,
      .cookie = &cookie,
      .lbf = EOF,
      .lock = -1,
  };

  CHECK(setvbuf(&stream, (char *)user_buffer, _IOFBF,
                sizeof(user_buffer)) == 0);
  CHECK(fputs("abc", &stream) == 0);
  CHECK(cookie.length == 0);
  CHECK(fflush(&stream) == 0);
  CHECK(cookie.length == 3);
  CHECK(memcmp(cookie.data, "abc", 3) == 0);

  setbuf(&stream, NULL);
  CHECK(fputc('Z', &stream) == 'Z');
  CHECK(cookie.length == 4 && cookie.data[3] == 'Z');
  errno = 0;
  CHECK(fileno(&stream) == -1);
  CHECK(errno == EBADF);
  CHECK(fclose(&stream) == 0);
  CHECK(fputc('X', &stream) == EOF);
}

static void test_fd_streams(void) {
  const KFILE_FD_OPS operations = {
      .open = virtual_open,
      .read = virtual_read,
      .write = virtual_write,
      .seek = virtual_seek,
      .close = virtual_close,
  };
  memset(&virtual_file, 0, sizeof(virtual_file));
  CHECK(kfile_set_fd_ops(&operations) == 0);

  FILE *stream = fopen("memory", "w+");
  CHECK(stream != NULL);
  if (stream == NULL) {
    return;
  }
  CHECK(fileno(stream) == 7);
  errno = 0;
  CHECK(kfile_set_fd_ops(NULL) == -1);
  CHECK(errno == EBUSY);
  CHECK(fwrite("abc", 1, 3, stream) == 3);
  CHECK(virtual_file.length == 0);
  CHECK(ftell(stream) == 3L);

  CHECK(fflush(NULL) == 0);
  CHECK(virtual_file.length == 3);
  CHECK(memcmp(virtual_file.data, "abc", 3) == 0);
  CHECK(fseek(stream, 0L, SEEK_SET) == 0);

  char data[8] = {0};
  CHECK(fread(data, 1, 3, stream) == 3);
  CHECK(memcmp(data, "abc", 3) == 0);
  CHECK(ftell(stream) == 3L);
  CHECK(ungetc('Q', stream) == 'Q');
  CHECK(ftell(stream) == 2L);
  CHECK(fgetc(stream) == 'Q');
  CHECK(ftell(stream) == 3L);

  rewind(stream);
  CHECK(ferror(stream) == 0);
  memset(data, 0, sizeof(data));
  CHECK(fgets(data, sizeof(data), stream) == data);
  CHECK(strcmp(data, "abc") == 0);

  /* freopen must flush/close the old association before a same-path open
   * with "w+" truncates it.  Otherwise this buffered text reappears after
   * the truncation. */
  CHECK(fputs("stale", stream) == 0);
  CHECK(freopen("memory", "w+", stream) == stream);
  CHECK(virtual_file.closes == 1);
  CHECK(virtual_file.length == 0);
  CHECK(fileno(stream) == 7);
  CHECK(fputs("xy", stream) == 0);
  CHECK(fclose(stream) == 0);
  CHECK(virtual_file.length == 2);
  CHECK(memcmp(virtual_file.data, "xy", 2) == 0);
  CHECK(virtual_file.opens == 2);
  CHECK(virtual_file.closes == 2);

  stream = fopen("memory", "a+");
  CHECK(stream != NULL);
  if (stream != NULL) {
    CHECK(fseek(stream, 0L, SEEK_SET) == 0);
    CHECK(fputc('!', stream) == '!');
    CHECK(ftell(stream) == 3L);
    CHECK(fflush(stream) == 0);
    CHECK(ftell(stream) == 3L);
    CHECK(virtual_file.length == 3);
    CHECK(memcmp(virtual_file.data, "xy!", 3) == 0);
    CHECK(fclose(stream) == 0);
  }
  CHECK(virtual_file.opens == 3);
  CHECK(virtual_file.closes == 3);

  memcpy(virtual_file.data, "abcdef", 6);
  virtual_file.length = 6;
  virtual_file.position = 0;
  stream = fopen("memory", "r");
  CHECK(stream != NULL);
  if (stream != NULL) {
    CHECK(fgetc(stream) == 'a');
    CHECK(virtual_file.position == 6);
    CHECK(fclose(stream) == 0);
    /* Closing a buffered input stream restores the shared descriptor's
     * logical position rather than leaking the read-ahead position. */
    CHECK(virtual_file.position == 1);
  }
  CHECK(virtual_file.opens == 4);
  CHECK(virtual_file.closes == 4);

  CHECK(kfile_set_fd_ops(NULL) == 0);
  errno = 0;
  CHECK(fopen("memory", "r") == NULL);
  CHECK(errno == ENOSYS);
}

static void test_output_streams(void) {
  static const char stdout_data[] = "[fwrite stdout]";
  static const char stderr_data[] = "[fwrite stderr]";

  clearerr(stdout);
  clearerr(stderr);

  CHECK(fputc('[', stdout) == '[');
  CHECK(putc('P', stdout) == 'P');
  CHECK(fputs("fputs stdout]", stdout) >= 0);
  CHECK(fwrite(stdout_data, 3, (sizeof(stdout_data) - 1) / 3, stdout) ==
        (sizeof(stdout_data) - 1) / 3);
  CHECK(fputc('\n', stdout) == '\n');
  CHECK(fprintf(stdout, "[fprintf %d]", 42) == 12);
  CHECK(kfprintf(kstdout, "[kfprintf %s]", "ok") == 13);
  CHECK(fflush(stdout) == 0);
  CHECK(ferror(stdout) == 0);

  CHECK(fputc('[', stderr) == '[');
  CHECK(fputs("fputs stderr]", stderr) >= 0);
  CHECK(fwrite(stderr_data, 3, (sizeof(stderr_data) - 1) / 3, stderr) ==
        (sizeof(stderr_data) - 1) / 3);
  CHECK(fputc('\n', stderr) == '\n');
  CHECK(fflush(stderr) == 0);
  CHECK(ferror(stderr) == 0);

  clearerr(stdout);
  errno = 0;
  const char invalid_format[] = "%q";
  CHECK(fprintf(stdout, invalid_format) == -1);
  CHECK(ferror(stdout) == 0);
  CHECK(errno == EINVAL);
}

static void test_sticky_error_and_eof(void) {
  clearerr(stdin);
  errno = 0;

  CHECK(fputc('X', stdin) == EOF);
  CHECK(ferror(stdin) != 0);
  CHECK(feof(stdin) == 0);
  CHECK(fprintf(stdin, "%s", "blocked") == -1);
  CHECK(ferror(stdin) != 0);
  CHECK(fgetc(stdin) == EOF);
  CHECK(ferror(stdin) != 0);
  CHECK(feof(stdin) != 0);

  clearerr(stdin);
  CHECK(ferror(stdin) == 0);
  CHECK(feof(stdin) == 0);

  CHECK(fgetc(stdin) == EOF);
  CHECK(feof(stdin) != 0);
  CHECK(ferror(stdin) == 0);
  CHECK(fgetc(stdin) == EOF);
  CHECK(feof(stdin) != 0);

  clearerr(stdin);
  CHECK(feof(stdin) == 0);
  CHECK(ferror(stdin) == 0);
}

static void test_ungetc(void) {
  unsigned char pushed[512];
  size_t pushed_count = 0;

  clearerr(stdin);
  CHECK(fgetc(stdin) == EOF);
  CHECK(feof(stdin) != 0);
  CHECK(ungetc(EOF, stdin) == EOF);
  CHECK(feof(stdin) != 0);

  CHECK(ungetc('Q', stdin) == 'Q');
  CHECK(feof(stdin) == 0);
  CHECK(getc(stdin) == 'Q');
  CHECK(ferror(stdin) == 0);

  CHECK(ungetc('G', stdin) == 'G');
  CHECK(getchar() == 'G');

  clearerr(stdin);
  while (pushed_count < sizeof(pushed)) {
    unsigned char value = (unsigned char)('A' + pushed_count % 26);
    int result = ungetc(value, stdin);
    if (result == EOF) {
      break;
    }
    CHECK(result == value);
    pushed[pushed_count++] = value;
  }

  /* 标准至少保证一字节；本实现使用有限的固定容量回退缓冲。 */
  CHECK(pushed_count >= 1);
  CHECK(pushed_count < sizeof(pushed));
  CHECK(feof(stdin) == 0);

  while (pushed_count != 0) {
    pushed_count--;
    CHECK(fgetc(stdin) == pushed[pushed_count]);
  }
  CHECK(fgetc(stdin) == EOF);
  CHECK(feof(stdin) != 0);
  CHECK(ferror(stdin) == 0);
  clearerr(stdin);
}

static void test_input_helpers(void) {
  unsigned char bytes[8];
  char line[8] = "keep";

  clearerr(stdin);
  CHECK(fread(bytes, 0, SIZE_MAX, stdin) == 0);
  CHECK(feof(stdin) == 0);
  CHECK(ferror(stdin) == 0);

  CHECK(fread(bytes, 1, sizeof(bytes), stdin) == 0);
  CHECK(feof(stdin) != 0);
  CHECK(ferror(stdin) == 0);

  clearerr(stdin);
  CHECK(fgets(line, sizeof(line), stdin) == NULL);
  CHECK(feof(stdin) != 0);
  CHECK(ferror(stdin) == 0);
  clearerr(stdin);
}

static void test_size_overflow(void) {
  unsigned char byte = 0x5a;

  clearerr(stdout);
  errno = 0;
  CHECK(fwrite(&byte, SIZE_MAX, 2, stdout) == 0);
  CHECK(ferror(stdout) != 0);
  CHECK(errno == EOVERFLOW);
  CHECK(fflush(stdout) == 0);
  CHECK(ferror(stdout) != 0);
  clearerr(stdout);
  CHECK(ferror(stdout) == 0);

  clearerr(stdin);
  errno = 0;
  CHECK(fread(&byte, SIZE_MAX, 2, stdin) == 0);
  CHECK(ferror(stdin) != 0);
  CHECK(feof(stdin) == 0);
  CHECK(errno == EOVERFLOW);
  clearerr(stdin);
  CHECK(ferror(stdin) == 0);
}

static void report_result(void) {
  size_t i;

  clearerr(stdout);
  clearerr(stderr);
  for (i = 0; i < failed_line_count; i++) {
    printf("file-stream-test check failed at line %d\n", failed_lines[i]);
  }
  printf(failures == 0 ? "FILE STREAM TEST PASS\n"
                       : "FILE STREAM TEST FAIL, failures=%d\n",
         failures);
  fflush(NULL);
}
#endif

int main() {
#if defined(__ISA_NATIVE__) && !defined(__NATIVE_USE_KLIB__)
  printf("file-stream-test skipped: native libc is active\n");
  return 0;
#else
  test_short_write_backend();
  test_short_repeat_backend();
  test_line_buffer();
  test_memory_read_backend();
  test_close_unseekable_input();
  test_buffer_configuration();
  test_fd_streams();
  test_output_streams();
  test_sticky_error_and_eof();
  test_ungetc();
  test_input_helpers();
  test_size_overflow();

  CHECK(fflush(NULL) == 0);
  errno = EINVAL;
  perror("file-stream-test perror");
  perror(NULL);

  report_result();
  halt(failures == 0 ? 0 : 1);
  return failures == 0 ? 0 : 1;
#endif
}
