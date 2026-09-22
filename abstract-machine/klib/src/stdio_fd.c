#include <errno.h>
#include <klib.h>

#include "stdio_impl.h"

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)

static KFILE_FD_OPS fd_ops;

size_t __kfile_stdin_read(FILE *stream, unsigned char *buf, size_t len)
{
  if (fd_ops.read == NULL)
  {
    return 0;
  }
  int saved_errno = errno;
  errno = 0;
  ptrdiff_t result = fd_ops.read(stream->fd, buf, len);
  if (result < 0 && errno == 0)
  {
    errno = EIO;
  }
  else if (result >= 0)
  {
    errno = saved_errno;
  }
  return result < 0 ? (size_t)-1 : (size_t)result;
}

static int valid_mode(const char *mode)
{
  if (mode == NULL || (*mode != 'r' && *mode != 'w' && *mode != 'a'))
  {
    return 0;
  }
  int seen_plus = 0;
  int seen_binary = 0;
  int seen_exclusive = 0;
  int seen_cloexec = 0;
  for (const char *p = mode + 1; *p != '\0'; p++)
  {
    int *seen = NULL;
    if (*p == '+') seen = &seen_plus;
    else if (*p == 'b') seen = &seen_binary;
    else if (*p == 'x') seen = &seen_exclusive;
    else if (*p == 'e') seen = &seen_cloexec;
    else return 0;
    if (*seen)
    {
      return 0;
    }
    *seen = 1;
  }
  return !seen_exclusive || *mode == 'w';
}

static unsigned mode_flags(const char *mode)
{
  unsigned flags = *mode == 'a' ? F_APPEND : 0;
  if (strchr(mode, '+') != NULL)
  {
    return flags;
  }
  return flags | (*mode == 'r' ? F_NOWR : F_NORD);
}

int kfile_set_fd_ops(const KFILE_FD_OPS *ops)
{
  if (__kfile_has_open_streams())
  {
    errno = EBUSY;
    return -1;
  }
  if (ops == NULL)
  {
    memset(&fd_ops, 0, sizeof(fd_ops));
    return 0;
  }
  fd_ops = *ops;
  return 0;
}

int remove(const char *path)
{
  if (path == NULL)
  {
    errno = EINVAL;
    return -1;
  }
  if (fd_ops.remove == NULL)
  {
    errno = ENOSYS;
    return -1;
  }
  return fd_ops.remove(path);
}

int rename(const char *old_path, const char *new_path)
{
  if (old_path == NULL || new_path == NULL)
  {
    errno = EINVAL;
    return -1;
  }
  if (fd_ops.rename == NULL)
  {
    errno = ENOSYS;
    return -1;
  }
  return fd_ops.rename(old_path, new_path);
}

FILE *tmpfile(void)
{
  errno = ENOSYS;
  return NULL;
}

char *tmpnam(char *s)
{
  (void)s;
  errno = ENOSYS;
  return NULL;
}

static size_t fd_read(FILE *stream, unsigned char *buf, size_t len)
{
  if (fd_ops.read == NULL)
  {
    errno = ENOSYS;
    return (size_t)-1;
  }
  int saved_errno = errno;
  errno = 0;
  ptrdiff_t result = fd_ops.read(stream->fd, buf, len);
  if (result < 0 && errno == 0)
  {
    errno = EIO;
  }
  else if (result >= 0)
  {
    errno = saved_errno;
  }
  return result < 0 ? (size_t)-1 : (size_t)result;
}

static size_t fd_write(FILE *stream, const unsigned char *buf, size_t len)
{
  if (fd_ops.write == NULL)
  {
    errno = ENOSYS;
    return (size_t)-1;
  }
  if ((stream->flags & F_APPEND) != 0 &&
      fd_ops.seek == NULL)
  {
    errno = ESPIPE;
    return (size_t)-1;
  }
  if ((stream->flags & F_APPEND) != 0)
  {
    int saved_errno = errno;
    errno = 0;
    if (fd_ops.seek(stream->fd, 0, SEEK_END) < 0)
    {
      if (errno == 0) errno = ESPIPE;
      return (size_t)-1;
    }
    errno = saved_errno;
  }
  int saved_errno = errno;
  errno = 0;
  ptrdiff_t result = fd_ops.write(stream->fd, buf, len);
  if (result < 0 && errno == 0)
  {
    errno = EIO;
  }
  else if (result >= 0)
  {
    errno = saved_errno;
  }
  return result < 0 ? (size_t)-1 : (size_t)result;
}

static int64_t fd_seek(FILE *stream, int64_t offset, int whence)
{
  if (fd_ops.seek == NULL)
  {
    errno = ENOSYS;
    return -1;
  }
  return fd_ops.seek(stream->fd, offset, whence);
}

static int fd_close(FILE *stream)
{
  if (fd_ops.close == NULL)
  {
    errno = ENOSYS;
    return -1;
  }
  return fd_ops.close(stream->fd);
}

FILE *fdopen(int fd, const char *mode)
{
  if (fd < 0 || !valid_mode(mode))
  {
    errno = EINVAL;
    return NULL;
  }

  unsigned access = mode_flags(mode);
  if (((access & F_NORD) == 0 && fd_ops.read == NULL) ||
      ((access & F_NOWR) == 0 && fd_ops.write == NULL) ||
      ((access & F_APPEND) != 0 && fd_ops.seek == NULL) ||
      fd_ops.close == NULL)
  {
    errno = ENOSYS;
    return NULL;
  }

  FILE *stream = (FILE *)calloc(1, sizeof(*stream));
  if (stream == NULL)
  {
    return NULL;
  }
  unsigned char *buffer = (unsigned char *)malloc(BUFSIZ);
  if (buffer == NULL)
  {
    free(stream);
    return NULL;
  }

  stream->flags = access | F_OWN_BUF | F_HAS_FD | F_DYNAMIC;
  stream->buf = buffer;
  stream->buf_size = BUFSIZ;
  stream->rpos = buffer;
  stream->rend = buffer;
  stream->wbase = buffer;
  stream->wpos = buffer;
  stream->wend = buffer + BUFSIZ;
  stream->read = fd_read;
  stream->write = fd_write;
  stream->seek = fd_seek;
  stream->close = fd_close;
  stream->fd = fd;
  stream->lbf = EOF;
  stream->lock = -1;
  __kfile_register(stream);
  return stream;
}

FILE *fopen(const char *path, const char *mode)
{
  if (path == NULL || !valid_mode(mode))
  {
    errno = EINVAL;
    return NULL;
  }
  if (fd_ops.open == NULL)
  {
    errno = ENOSYS;
    return NULL;
  }

  unsigned access = mode_flags(mode);
  if (((access & F_NORD) == 0 && fd_ops.read == NULL) ||
      ((access & F_NOWR) == 0 && fd_ops.write == NULL) ||
      ((access & F_APPEND) != 0 && fd_ops.seek == NULL) ||
      fd_ops.close == NULL)
  {
    errno = ENOSYS;
    return NULL;
  }

  int fd = fd_ops.open(path, mode);
  if (fd < 0)
  {
    return NULL;
  }
  FILE *stream = fdopen(fd, mode);
  if (stream == NULL && fd_ops.close != NULL)
  {
    int saved_errno = errno;
    (void)fd_ops.close(fd);
    errno = saved_errno;
  }
  return stream;
}

FILE *freopen(const char *path, const char *mode, FILE *stream)
{
  if (path == NULL || stream == NULL || !valid_mode(mode))
  {
    errno = EINVAL;
    return NULL;
  }
  if (fd_ops.open == NULL)
  {
    errno = ENOSYS;
    return NULL;
  }

  unsigned access = mode_flags(mode);
  if (((access & F_NORD) == 0 && fd_ops.read == NULL) ||
      ((access & F_NOWR) == 0 && fd_ops.write == NULL) ||
      ((access & F_APPEND) != 0 && fd_ops.seek == NULL) ||
      fd_ops.close == NULL)
  {
    errno = ENOSYS;
    return NULL;
  }

  /*
   * C requires the old association to be closed before the new file is
   * opened.  The ordering is observable when path names the same file and
   * the new mode truncates it: pending old output must be flushed first,
   * then the new open may truncate it.
   */
  unsigned preserved = stream->flags &
      (F_PERM | F_DYNAMIC | F_REGISTERED);
  FILE *prev = stream->prev;
  FILE *next = stream->next;
  (void)fflush(stream);
  if ((stream->flags & F_HAS_FD) != 0 && stream->close != NULL)
  {
    (void)stream->close(stream);
  }
  if ((stream->flags & F_OWN_BUF) != 0)
  {
    free(stream->buf);
  }

  /* Leave a failed freopen as a closed stream while retaining list links so
   * a later fclose/exit can still reclaim a dynamically allocated FILE. */
  memset(stream, 0, sizeof(*stream));
  stream->flags = preserved | F_NORD | F_NOWR;
  stream->prev = prev;
  stream->next = next;
  stream->fd = -1;
  stream->lbf = EOF;
  stream->lock = -1;

  int new_fd = fd_ops.open(path, mode);
  if (new_fd < 0)
  {
    return NULL;
  }

  unsigned char *new_buffer = (unsigned char *)malloc(BUFSIZ);
  if (new_buffer == NULL)
  {
    int saved_errno = errno;
    (void)fd_ops.close(new_fd);
    errno = saved_errno;
    return NULL;
  }

  stream->flags = preserved | access | F_OWN_BUF | F_HAS_FD;
  stream->buf = new_buffer;
  stream->buf_size = BUFSIZ;
  stream->rpos = new_buffer;
  stream->rend = new_buffer;
  stream->wbase = new_buffer;
  stream->wpos = new_buffer;
  stream->wend = new_buffer + BUFSIZ;
  stream->read = fd_read;
  stream->write = fd_write;
  stream->seek = fd_seek;
  stream->close = fd_close;
  stream->fd = new_fd;
  stream->lbf = EOF;
  stream->lock = -1;
  __kfile_register(stream);
  return stream;
}

#endif
