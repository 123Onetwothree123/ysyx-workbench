#include <am.h>
#include <klib.h>
#include <errno.h>
#include <limits.h>
#include <stdint.h>

#include "stdio_impl.h"

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)

#define KFILE_BUFFER_SIZE ((size_t)BUFSIZ)

static size_t console_write(FILE *f, const unsigned char *buf, size_t len);
static size_t console_repeat(FILE *f, unsigned char ch, size_t len);

static unsigned char stdin_storage[KFILE_BUFFER_SIZE];
static unsigned char stdout_storage[KFILE_BUFFER_SIZE];

static FILE stdin_file = {
    .flags = F_PERM | F_NOWR | F_HAS_FD,
    .rpos = stdin_storage,
    .rend = stdin_storage,
    .buf = stdin_storage,
    .buf_size = KFILE_BUFFER_SIZE,
    .read = __kfile_stdin_read,
    .fd = 0,
    .lbf = EOF,
    .lock = -1,
};

static FILE stdout_file = {
    .flags = F_PERM | F_NORD | F_HAS_FD,
    .wbase = stdout_storage,
    .wpos = stdout_storage,
    .wend = stdout_storage + KFILE_BUFFER_SIZE,
    .buf = stdout_storage,
    .buf_size = KFILE_BUFFER_SIZE,
    .write = console_write,
    .repeat = console_repeat,
    .fd = 1,
    .lbf = '\n',
    .lock = -1,
};

static FILE stderr_file = {
    .flags = F_PERM | F_NORD | F_HAS_FD,
    .write = console_write,
    .repeat = console_repeat,
    .fd = 2,
    .lbf = EOF,
    .lock = -1,
};

FILE *stdin = &stdin_file;
FILE *stdout = &stdout_file;
FILE *stderr = &stderr_file;
KFILE *kstdin = &stdin_file;
KFILE *kstdout = &stdout_file;
KFILE *kstderr = &stderr_file;

static FILE *open_streams;

static void set_stream_error(FILE *f, int error_number)
{
  if (f != NULL)
  {
    f->flags |= F_ERR;
  }
  if (error_number != 0)
  {
    errno = error_number;
  }
}

static int64_t backend_seek(
    FILE *f,
    int64_t offset,
    int whence,
    int fallback_error)
{
  if (f == NULL || f->seek == NULL)
  {
    set_stream_error(f, ESPIPE);
    return -1;
  }
  int saved_errno = errno;
  errno = 0;
  int64_t result = f->seek(f, offset, whence);
  if (result < 0)
  {
    set_stream_error(f, errno == 0 ? fallback_error : 0);
  }
  else
  {
    errno = saved_errno;
  }
  return result;
}

void __kfile_lock(FILE *f)
{
  /* Replaced by the scheduler/IRQ-aware lock once those facilities exist. */
  (void)f;
}

void __kfile_unlock(FILE *f)
{
  (void)f;
}

void __kfile_register(FILE *f)
{
  if (f == NULL || (f->flags & F_REGISTERED) != 0)
  {
    return;
  }
  f->prev = NULL;
  f->next = open_streams;
  if (open_streams != NULL)
  {
    open_streams->prev = f;
  }
  open_streams = f;
  f->flags |= F_REGISTERED;
}

void __kfile_unregister(FILE *f)
{
  if (f == NULL || (f->flags & F_REGISTERED) == 0)
  {
    return;
  }
  if (f->prev != NULL)
  {
    f->prev->next = f->next;
  }
  else
  {
    open_streams = f->next;
  }
  if (f->next != NULL)
  {
    f->next->prev = f->prev;
  }
  f->prev = NULL;
  f->next = NULL;
  f->flags &= ~F_REGISTERED;
}

int __kfile_has_open_streams(void)
{
  return open_streams != NULL;
}

void __kfile_close_all(void)
{
  while (open_streams != NULL)
  {
    (void)fclose(open_streams);
  }
}

static size_t console_write(FILE *f, const unsigned char *buf, size_t len)
{
  (void)f;
  for (size_t i = 0; i < len; i++)
  {
    putch((char)buf[i]);
  }
  return len;
}

static size_t console_repeat(FILE *f, unsigned char ch, size_t len)
{
  (void)f;
  for (size_t i = 0; i < len; i++)
  {
    putch((char)ch);
  }
  return len;
}

static void reset_write_buffer(FILE *f)
{
  if (f->buf != NULL && f->buf_size != 0)
  {
    f->wbase = f->buf;
    f->wpos = f->buf;
    f->wend = f->buf + f->buf_size;
  }
  else
  {
    f->wbase = NULL;
    f->wpos = NULL;
    f->wend = NULL;
  }
}

static size_t pending_output(const FILE *f)
{
  if (f->wbase == NULL || f->wpos == NULL ||
      (uintptr_t)f->wpos < (uintptr_t)f->wbase)
  {
    return 0;
  }
  return (size_t)((uintptr_t)f->wpos - (uintptr_t)f->wbase);
}

/*
 * Flushes the current write buffer and reports how many pending bytes reached
 * the backend.  On a short write, the unconsumed suffix remains buffered.
 */
static int flush_write_buffer(FILE *f, size_t *sent_out)
{
  size_t pending = pending_output(f);
  size_t sent = 0;
  if (sent_out != NULL)
  {
    *sent_out = 0;
  }
  if (pending == 0)
  {
    return 0;
  }
  if (f->write == NULL)
  {
    set_stream_error(f, EBADF);
    return EOF;
  }

  while (sent < pending)
  {
    size_t n = f->write(f, f->wbase + sent, pending - sent);
    if (n == (size_t)-1)
    {
      set_stream_error(f, errno == 0 ? EIO : 0);
      break;
    }
    if (n == 0 || n > pending - sent)
    {
      set_stream_error(f, EIO);
      break;
    }
    sent += n;
  }

  if (sent != 0 && sent < pending)
  {
    memmove(f->wbase, f->wbase + sent, pending - sent);
  }
  f->wpos = f->wbase + (pending - sent);
  if (sent_out != NULL)
  {
    *sent_out = sent;
  }
  return sent == pending ? 0 : EOF;
}

static size_t backend_write(FILE *f, const unsigned char *buf, size_t len)
{
  size_t written = 0;
  while (written < len)
  {
    size_t n = f->write(f, buf + written, len - written);
    if (n == (size_t)-1)
    {
      set_stream_error(f, errno == 0 ? EIO : 0);
      break;
    }
    if (n == 0 || n > len - written)
    {
      set_stream_error(f, EIO);
      break;
    }
    written += n;
  }
  return written;
}

static int prepare_write(FILE *f)
{
  if (f == NULL)
  {
    errno = EINVAL;
    return EOF;
  }
  if ((f->flags & F_NOWR) != 0 || f->write == NULL)
  {
    set_stream_error(f, EBADF);
    return EOF;
  }

  size_t unread = f->unget_count;
  if (f->rpos != NULL && f->rend != NULL &&
      (uintptr_t)f->rend > (uintptr_t)f->rpos)
  {
    size_t buffered = (size_t)((uintptr_t)f->rend - (uintptr_t)f->rpos);
    if (buffered > SIZE_MAX - unread)
    {
      set_stream_error(f, EOVERFLOW);
      return EOF;
    }
    unread += buffered;
  }
  if (unread > (size_t)INT64_MAX)
  {
    set_stream_error(f, EOVERFLOW);
    return EOF;
  }
  if (unread != 0 &&
      backend_seek(f, -(int64_t)unread, SEEK_CUR, ESPIPE) < 0)
  {
    return EOF;
  }
  f->rpos = f->buf;
  f->rend = f->buf;
  f->unget_count = 0;

  /* Establish the logical position at EOF before the first byte enters an
   * append buffer.  fd_write repeats this immediately before the backend
   * write so concurrent growth cannot turn an append into an overwrite. */
  if ((f->flags & F_APPEND) != 0 && pending_output(f) == 0 &&
      backend_seek(f, 0, SEEK_END, ESPIPE) < 0)
  {
    return EOF;
  }

  if (f->buf != NULL && f->buf_size != 0 &&
      (f->wbase == NULL || f->wpos == NULL || f->wend == NULL))
  {
    reset_write_buffer(f);
  }
  return 0;
}

size_t __kfile_write(FILE *f, const void *data, size_t len)
{
  const unsigned char *buf = (const unsigned char *)data;
  size_t consumed = 0;
  if (len == 0)
  {
    return 0;
  }
  if (buf == NULL || prepare_write(f) == EOF)
  {
    if (buf == NULL)
    {
      set_stream_error(f, EINVAL);
    }
    return 0;
  }

  if (f->buf == NULL || f->buf_size == 0)
  {
    return backend_write(f, buf, len);
  }

  while (consumed < len)
  {
    size_t old_pending = pending_output(f);
    if (old_pending >= f->buf_size)
    {
      if (flush_write_buffer(f, NULL) == EOF)
      {
        break;
      }
      old_pending = 0;
    }

    /* A full-buffer direct write avoids a redundant copy for binary output. */
    if (old_pending == 0 && f->lbf == EOF &&
        len - consumed >= f->buf_size)
    {
      size_t n = backend_write(f, buf + consumed, len - consumed);
      consumed += n;
      break;
    }

    size_t amount = f->buf_size - old_pending;
    if (amount > len - consumed)
    {
      amount = len - consumed;
    }

    int flush_now = amount == f->buf_size - old_pending;
    if (f->lbf != EOF)
    {
      unsigned char line_byte = (unsigned char)f->lbf;
      for (size_t i = 0; i < amount; i++)
      {
        if (buf[consumed + i] == line_byte)
        {
          amount = i + 1;
          flush_now = 1;
          break;
        }
      }
    }

    memcpy(f->wpos, buf + consumed, amount);
    f->wpos += amount;
    if (!flush_now)
    {
      consumed += amount;
      continue;
    }

    size_t sent = 0;
    if (flush_write_buffer(f, &sent) == EOF)
    {
      size_t old_sent = sent < old_pending ? sent : old_pending;
      size_t old_remaining = old_pending - old_sent;
      size_t newly_sent = sent > old_pending ? sent - old_pending : 0;
      if (newly_sent > amount)
      {
        newly_sent = amount;
      }
      /* Drop unreported bytes from this call; retain older pending output. */
      f->wpos = f->wbase + old_remaining;
      consumed += newly_sent;
      break;
    }
    consumed += amount;
  }
  return consumed;
}

size_t __kfile_repeat(FILE *f, unsigned char ch, size_t len)
{
  if (len == 0)
  {
    return 0;
  }
  if (prepare_write(f) == EOF)
  {
    return 0;
  }

  /* Direct repeat is an optimization for unbuffered sinks such as
   * snprintf. Buffered streams must preserve their buffering semantics. */
  if (f->repeat != NULL && (f->buf == NULL || f->buf_size == 0))
  {
    if (flush_write_buffer(f, NULL) == EOF)
    {
      return 0;
    }
    size_t total = 0;
    while (total < len)
    {
      size_t n = f->repeat(f, ch, len - total);
      if (n == (size_t)-1)
      {
        set_stream_error(f, errno == 0 ? EIO : 0);
        break;
      }
      if (n == 0 || n > len - total)
      {
        set_stream_error(f, EIO);
        break;
      }
      total += n;
    }
    return total;
  }

  unsigned char repeated[64];
  memset(repeated, ch, sizeof(repeated));
  size_t total = 0;
  while (total < len)
  {
    size_t amount = len - total;
    if (amount > sizeof(repeated))
    {
      amount = sizeof(repeated);
    }
    size_t n = __kfile_write(f, repeated, amount);
    total += n;
    if (n != amount)
    {
      break;
    }
  }
  return total;
}

static int prepare_read(FILE *f)
{
  if (f == NULL)
  {
    errno = EINVAL;
    return EOF;
  }
  if ((f->flags & F_NORD) != 0 || f->read == NULL)
  {
    set_stream_error(f, EBADF);
    return EOF;
  }
  if (pending_output(f) != 0 && flush_write_buffer(f, NULL) == EOF)
  {
    return EOF;
  }
  if (f->rpos == NULL || f->rend == NULL)
  {
    f->rpos = f->buf;
    f->rend = f->buf;
  }
  return 0;
}

static size_t backend_read(FILE *f, unsigned char *buf, size_t len)
{
  size_t n = f->read(f, buf, len);
  if (n == (size_t)-1)
  {
    set_stream_error(f, errno == 0 ? EIO : 0);
    return 0;
  }
  if (n > len)
  {
    set_stream_error(f, EIO);
    return 0;
  }
  if (n == 0)
  {
    f->flags |= F_EOF;
  }
  return n;
}

static size_t stream_read(FILE *f, unsigned char *dst, size_t len)
{
  size_t total = 0;
  if (len == 0)
  {
    return 0;
  }
  if (dst == NULL || prepare_read(f) == EOF)
  {
    if (dst == NULL)
    {
      set_stream_error(f, EINVAL);
    }
    return 0;
  }
  if ((f->flags & F_EOF) != 0)
  {
    return 0;
  }

  while (total < len)
  {
    while (total < len && f->unget_count != 0)
    {
      dst[total++] = f->unget[--f->unget_count];
    }
    if (total == len)
    {
      break;
    }

    size_t available = 0;
    if (f->rpos != NULL && f->rend != NULL &&
        (uintptr_t)f->rend >= (uintptr_t)f->rpos)
    {
      available = (size_t)((uintptr_t)f->rend - (uintptr_t)f->rpos);
    }
    if (available != 0)
    {
      size_t amount = len - total;
      if (amount > available)
      {
        amount = available;
      }
      memcpy(dst + total, f->rpos, amount);
      f->rpos += amount;
      total += amount;
      continue;
    }

    if (f->buf == NULL || f->buf_size == 0 || len - total >= f->buf_size)
    {
      size_t n = backend_read(f, dst + total, len - total);
      total += n;
      if (n == 0)
      {
        break;
      }
      continue;
    }

    size_t n = backend_read(f, f->buf, f->buf_size);
    f->rpos = f->buf;
    f->rend = f->buf + n;
    if (n == 0)
    {
      break;
    }
  }
  return total;
}

static int flush_stream_unlocked(FILE *stream, int strict_input_sync)
{
  int result = 0;
  if (pending_output(stream) != 0)
  {
    result = flush_write_buffer(stream, NULL);
  }
  else
  {
    size_t unread = stream->unget_count;
    if (stream->rpos != NULL && stream->rend != NULL &&
        (uintptr_t)stream->rend > (uintptr_t)stream->rpos)
    {
      size_t buffered = (size_t)((uintptr_t)stream->rend -
                                 (uintptr_t)stream->rpos);
      if (buffered > SIZE_MAX - unread)
      {
        set_stream_error(stream, EOVERFLOW);
        return EOF;
      }
      unread += buffered;
    }
    if (unread > (size_t)INT64_MAX && strict_input_sync)
    {
      set_stream_error(stream, EOVERFLOW);
      result = EOF;
    }
    else if (unread != 0 && strict_input_sync &&
             backend_seek(stream, -(int64_t)unread, SEEK_CUR, ESPIPE) < 0)
    {
      result = EOF;
    }
    else
    {
      if (unread != 0 && !strict_input_sync &&
          unread <= (size_t)INT64_MAX && stream->seek != NULL)
      {
        /* Closing a readable pipe/TTY must not fail merely because its
         * read-ahead cannot be rewound.  Seekable shared descriptors still
         * get a best-effort logical-position repair. */
        int saved_errno = errno;
        (void)stream->seek(
            stream, -(int64_t)unread, SEEK_CUR);
        errno = saved_errno;
      }
      stream->rpos = stream->buf;
      stream->rend = stream->buf;
      stream->unget_count = 0;
    }
  }
  return result;
}

int fflush(FILE *stream)
{
  if (stream == NULL)
  {
    int result = 0;
    if (fflush(stdout) == EOF)
    {
      result = EOF;
    }
    if (fflush(stderr) == EOF)
    {
      result = EOF;
    }
    for (FILE *current = open_streams; current != NULL;)
    {
      FILE *next = current->next;
      if (pending_output(current) != 0 && fflush(current) == EOF)
      {
        result = EOF;
      }
      current = next;
    }
    return result;
  }

  __kfile_lock(stream);
  int result = flush_stream_unlocked(stream, 1);
  __kfile_unlock(stream);
  return result;
}

int setvbuf(FILE *stream, char *buf, int mode, size_t size)
{
  if (stream == NULL ||
      (mode != _IOFBF && mode != _IOLBF && mode != _IONBF) ||
      (mode != _IONBF && size == 0))
  {
    errno = EINVAL;
    return -1;
  }

  unsigned char *new_buffer = NULL;
  int new_buffer_owned = 0;
  if (mode != _IONBF)
  {
    new_buffer = (unsigned char *)buf;
    if (new_buffer == NULL)
    {
      new_buffer = (unsigned char *)malloc(size);
      if (new_buffer == NULL)
      {
        errno = ENOMEM;
        return -1;
      }
      new_buffer_owned = 1;
    }
  }

  if (fflush(stream) == EOF)
  {
    if (new_buffer_owned)
    {
      free(new_buffer);
    }
    return -1;
  }

  __kfile_lock(stream);
  unsigned char *old_buffer = stream->buf;
  int old_buffer_owned = (stream->flags & F_OWN_BUF) != 0;
  if (old_buffer_owned && old_buffer == new_buffer)
  {
    new_buffer_owned = 1;
  }

  stream->flags &= ~F_OWN_BUF;
  if (new_buffer_owned)
  {
    stream->flags |= F_OWN_BUF;
  }
  stream->buf = new_buffer;
  stream->buf_size = mode == _IONBF ? 0 : size;
  stream->unget_count = 0;
  stream->rpos = new_buffer;
  stream->rend = new_buffer;
  stream->lbf = mode == _IOLBF ? '\n' : EOF;
  reset_write_buffer(stream);
  __kfile_unlock(stream);

  if (old_buffer_owned && old_buffer != new_buffer)
  {
    free(old_buffer);
  }
  return 0;
}

void setbuf(FILE *stream, char *buf)
{
  (void)setvbuf(stream, buf, buf == NULL ? _IONBF : _IOFBF, BUFSIZ);
}

int fileno(FILE *stream)
{
  if (stream == NULL || (stream->flags & F_HAS_FD) == 0)
  {
    errno = EBADF;
    return -1;
  }
  return stream->fd;
}

int fclose(FILE *stream)
{
  if (stream == NULL)
  {
    errno = EINVAL;
    return EOF;
  }

  __kfile_lock(stream);
  int result = flush_stream_unlocked(stream, 0);
  if (stream->close != NULL && stream->close(stream) != 0)
  {
    result = EOF;
  }
  __kfile_unlock(stream);

  __kfile_unregister(stream);
  if ((stream->flags & F_OWN_BUF) != 0)
  {
    free(stream->buf);
    stream->flags &= ~F_OWN_BUF;
  }
  stream->buf = NULL;
  stream->buf_size = 0;
  stream->rpos = stream->rend = NULL;
  stream->wbase = stream->wpos = stream->wend = NULL;
  stream->unget_count = 0;

  if ((stream->flags & F_DYNAMIC) != 0)
  {
    free(stream);
  }
  else
  {
    stream->flags |= F_NORD | F_NOWR;
    stream->read = NULL;
    stream->write = NULL;
    stream->repeat = NULL;
    stream->seek = NULL;
    stream->close = NULL;
  }
  return result;
}

int fseek(FILE *stream, long offset, int whence)
{
  if (stream == NULL || stream->seek == NULL)
  {
    set_stream_error(stream, ESPIPE);
    return -1;
  }
  if (whence != SEEK_SET && whence != SEEK_CUR && whence != SEEK_END)
  {
    errno = EINVAL;
    return -1;
  }

  __kfile_lock(stream);
  if (pending_output(stream) != 0 && flush_write_buffer(stream, NULL) == EOF)
  {
    __kfile_unlock(stream);
    return -1;
  }

  int64_t adjusted = (int64_t)offset;
  if (whence == SEEK_CUR)
  {
    size_t unread = stream->unget_count;
    if (stream->rpos != NULL && stream->rend != NULL &&
        (uintptr_t)stream->rend > (uintptr_t)stream->rpos)
    {
      size_t buffered = (size_t)((uintptr_t)stream->rend -
                                 (uintptr_t)stream->rpos);
      if (buffered > SIZE_MAX - unread)
      {
        set_stream_error(stream, EOVERFLOW);
        __kfile_unlock(stream);
        return -1;
      }
      unread += buffered;
    }
    if (unread > (size_t)INT64_MAX || adjusted < INT64_MIN + (int64_t)unread)
    {
      set_stream_error(stream, EOVERFLOW);
      __kfile_unlock(stream);
      return -1;
    }
    adjusted -= (int64_t)unread;
  }

  if (backend_seek(stream, adjusted, whence, EIO) < 0)
  {
    __kfile_unlock(stream);
    return -1;
  }
  stream->rpos = stream->buf;
  stream->rend = stream->buf;
  stream->unget_count = 0;
  reset_write_buffer(stream);
  stream->flags &= ~F_EOF;
  __kfile_unlock(stream);
  return 0;
}

long ftell(FILE *stream)
{
  if (stream == NULL || stream->seek == NULL)
  {
    set_stream_error(stream, ESPIPE);
    return -1L;
  }

  __kfile_lock(stream);
  int64_t position = backend_seek(stream, 0, SEEK_CUR, EIO);
  if (position < 0)
  {
    __kfile_unlock(stream);
    return -1L;
  }

  size_t unread = stream->unget_count;
  if (stream->rpos != NULL && stream->rend != NULL &&
      (uintptr_t)stream->rend > (uintptr_t)stream->rpos)
  {
    size_t buffered = (size_t)((uintptr_t)stream->rend -
                               (uintptr_t)stream->rpos);
    if (buffered > SIZE_MAX - unread)
    {
      set_stream_error(stream, EOVERFLOW);
      __kfile_unlock(stream);
      return -1L;
    }
    unread += buffered;
  }
  size_t pending = pending_output(stream);
  if (unread > (size_t)INT64_MAX || position < (int64_t)unread ||
      pending > (size_t)(INT64_MAX - (position - (int64_t)unread)))
  {
    set_stream_error(stream, EOVERFLOW);
    __kfile_unlock(stream);
    return -1L;
  }
  position = position - (int64_t)unread + (int64_t)pending;
  if (position > LONG_MAX)
  {
    set_stream_error(stream, EOVERFLOW);
    __kfile_unlock(stream);
    return -1L;
  }
  __kfile_unlock(stream);
  return (long)position;
}

void rewind(FILE *stream)
{
  if (stream != NULL)
  {
    (void)fseek(stream, 0L, SEEK_SET);
    clearerr(stream);
  }
}

int fgetpos(FILE *stream, fpos_t *position)
{
  if (position == NULL)
  {
    errno = EINVAL;
    return -1;
  }
  long value = ftell(stream);
  if (value < 0)
  {
    return -1;
  }
  *position = value;
  return 0;
}

int fsetpos(FILE *stream, const fpos_t *position)
{
  if (position == NULL)
  {
    errno = EINVAL;
    return -1;
  }
  return fseek(stream, *position, SEEK_SET);
}

int fputc(int c, FILE *stream)
{
  unsigned char ch = (unsigned char)c;
  if (stream == NULL)
  {
    errno = EINVAL;
    return EOF;
  }
  __kfile_lock(stream);
  size_t n = __kfile_write(stream, &ch, 1);
  __kfile_unlock(stream);
  return n == 1 ? (int)ch : EOF;
}

int putc(int c, FILE *stream)
{
  return fputc(c, stream);
}

int fputs(const char *s, FILE *stream)
{
  if (s == NULL || stream == NULL)
  {
    set_stream_error(stream, EINVAL);
    return EOF;
  }
  size_t len = strlen(s);
  __kfile_lock(stream);
  size_t n = __kfile_write(stream, s, len);
  __kfile_unlock(stream);
  return n == len ? 0 : EOF;
}

size_t fwrite(const void *ptr, size_t size, size_t nmemb, FILE *stream)
{
  if (size == 0 || nmemb == 0)
  {
    return 0;
  }
  if (stream == NULL || ptr == NULL)
  {
    set_stream_error(stream, EINVAL);
    return 0;
  }
  if (nmemb > SIZE_MAX / size)
  {
    set_stream_error(stream, EOVERFLOW);
    return 0;
  }
  size_t len = size * nmemb;
  __kfile_lock(stream);
  size_t n = __kfile_write(stream, ptr, len);
  __kfile_unlock(stream);
  return n / size;
}

int fgetc(FILE *stream)
{
  unsigned char ch;
  if (stream == NULL)
  {
    errno = EINVAL;
    return EOF;
  }
  __kfile_lock(stream);
  size_t n = stream_read(stream, &ch, 1);
  __kfile_unlock(stream);
  return n == 1 ? (int)ch : EOF;
}

int getc(FILE *stream)
{
  return fgetc(stream);
}

int getchar(void)
{
  return fgetc(stdin);
}

size_t fread(void *ptr, size_t size, size_t nmemb, FILE *stream)
{
  if (size == 0 || nmemb == 0)
  {
    return 0;
  }
  if (stream == NULL || ptr == NULL)
  {
    set_stream_error(stream, EINVAL);
    return 0;
  }
  if (nmemb > SIZE_MAX / size)
  {
    set_stream_error(stream, EOVERFLOW);
    return 0;
  }
  size_t len = size * nmemb;
  __kfile_lock(stream);
  size_t n = stream_read(stream, (unsigned char *)ptr, len);
  __kfile_unlock(stream);
  return n / size;
}

char *fgets(char *s, int n, FILE *stream)
{
  if (s == NULL || stream == NULL || n <= 0)
  {
    set_stream_error(stream, EINVAL);
    return NULL;
  }
  if (n == 1)
  {
    s[0] = '\0';
    return s;
  }

  __kfile_lock(stream);
  int count = 0;
  while (count < n - 1)
  {
    unsigned char ch;
    if (stream_read(stream, &ch, 1) != 1)
    {
      break;
    }
    s[count++] = (char)ch;
    if (ch == '\n')
    {
      break;
    }
  }
  __kfile_unlock(stream);
  if (count == 0)
  {
    return NULL;
  }
  s[count] = '\0';
  return s;
}

int ungetc(int c, FILE *stream)
{
  if (c == EOF || stream == NULL)
  {
    if (stream == NULL)
    {
      errno = EINVAL;
    }
    return EOF;
  }

  __kfile_lock(stream);
  if ((stream->flags & F_NORD) != 0)
  {
    set_stream_error(stream, EBADF);
    __kfile_unlock(stream);
    return EOF;
  }
  if (stream->unget_count == KFILE_UNGET_MAX)
  {
    __kfile_unlock(stream);
    return EOF;
  }
  stream->unget[stream->unget_count++] = (unsigned char)c;
  stream->flags &= ~F_EOF;
  __kfile_unlock(stream);
  return (unsigned char)c;
}

int feof(FILE *stream)
{
  return stream != NULL && (stream->flags & F_EOF) != 0;
}

int ferror(FILE *stream)
{
  return stream != NULL && (stream->flags & F_ERR) != 0;
}

void clearerr(FILE *stream)
{
  if (stream != NULL)
  {
    stream->flags &= ~(F_EOF | F_ERR);
  }
}

int putchar(int c)
{
  return fputc(c, stdout);
}

int puts(const char *s)
{
  if (s == NULL)
  {
    set_stream_error(stdout, EINVAL);
    return EOF;
  }
  __kfile_lock(stdout);
  size_t len = strlen(s);
  size_t first = __kfile_write(stdout, s, len);
  unsigned char newline = '\n';
  size_t second = first == len ? __kfile_write(stdout, &newline, 1) : 0;
  __kfile_unlock(stdout);
  return first == len && second == 1 ? 0 : EOF;
}

void perror(const char *s)
{
  int saved_errno = errno;
  const char *message = strerror(saved_errno);
  __kfile_lock(stderr);
  if (s != NULL && *s != '\0')
  {
    (void)__kfile_write(stderr, s, strlen(s));
    (void)__kfile_write(stderr, ": ", 2);
  }
  (void)__kfile_write(stderr, message, strlen(message));
  (void)__kfile_write(stderr, "\n", 1);
  __kfile_unlock(stderr);
  errno = saved_errno;
}

#endif
