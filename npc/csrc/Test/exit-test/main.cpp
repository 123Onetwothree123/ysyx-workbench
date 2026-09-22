#include <am.h>
#include <klib.h>

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
static int sequence;
static char file_output[16];
static size_t file_output_length;

static int exit_file_open(const char *path, const char *mode)
{
  if (strcmp(path, "exit-test") != 0 || strcmp(mode, "w") != 0)
  {
    return -1;
  }
  return 9;
}

static ptrdiff_t exit_file_write(int fd, const void *data, size_t length)
{
  if (fd != 9 || length > sizeof(file_output) - file_output_length)
  {
    return -1;
  }
  memcpy(file_output + file_output_length, data, length);
  file_output_length += length;
  return (ptrdiff_t)length;
}

static int exit_file_close(int fd)
{
  if (fd != 9 || file_output_length != 7 ||
      memcmp(file_output, "pending", 7) != 0)
  {
    halt(1);
  }
  return 0;
}

static void last_handler(void)
{
  if (sequence != 2)
  {
    halt(1);
  }
  fputs("ATEXIT TEST PASS", stdout);
}

static void second_handler(void)
{
  if (sequence != 1)
  {
    halt(1);
  }
  sequence = 2;
}

static void first_handler(void)
{
  if (sequence != 0)
  {
    halt(1);
  }
  sequence = 1;
}
#endif

int main()
{
#if defined(__ISA_NATIVE__) && !defined(__NATIVE_USE_KLIB__)
  printf("exit-test skipped: native libc is active\n");
  return 0;
#else
  KFILE_FD_OPS operations{};
  operations.open = exit_file_open;
  operations.write = exit_file_write;
  operations.close = exit_file_close;
  if (kfile_set_fd_ops(&operations) != 0)
  {
    halt(1);
  }
  FILE *pending_file = fopen("exit-test", "w");
  if (pending_file == NULL || fputs("pending", pending_file) == EOF)
  {
    halt(1);
  }

  /* Handlers execute in reverse registration order. */
  if (atexit(last_handler) != 0 ||
      atexit(second_handler) != 0 ||
      atexit(first_handler) != 0)
  {
    halt(1);
  }
  exit(0);
#endif
}
