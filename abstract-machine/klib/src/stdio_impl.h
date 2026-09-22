#ifndef KLIB_STDIO_IMPL_H__
#define KLIB_STDIO_IMPL_H__

#include <klib.h>
#include <stddef.h>
#include <stdint.h>

#define KFILE_UNGET_MAX 8u

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)

#if defined(__ISA_NATIVE__)
typedef struct FILE FILE;
#endif

enum {
  F_PERM = 1u << 0,
  F_NORD = 1u << 1,
  F_NOWR = 1u << 2,
  F_EOF  = 1u << 3,
  F_ERR  = 1u << 4,
  F_OWN_BUF = 1u << 5,
  F_HAS_FD = 1u << 6,
  F_DYNAMIC = 1u << 7,
  F_REGISTERED = 1u << 8,
  F_APPEND = 1u << 9,
};

struct FILE {
  unsigned flags;

  unsigned char *rpos;
  unsigned char *rend;
  unsigned char *wbase;
  unsigned char *wpos;
  unsigned char *wend;

  unsigned char *buf;
  size_t buf_size;
  unsigned char unget[KFILE_UNGET_MAX];
  size_t unget_count;

  size_t (*read)(FILE *f, unsigned char *buf, size_t len);
  size_t (*write)(FILE *f, const unsigned char *buf, size_t len);
  size_t (*repeat)(FILE *f, unsigned char ch, size_t len);
  int64_t (*seek)(FILE *f, int64_t offset, int whence);
  int (*close)(FILE *f);

  int fd;
  void *cookie;
  FILE *prev;
  FILE *next;
  int lbf;
  int lock;
};

#ifdef __cplusplus
extern "C" {
#endif

/* Formatter-facing raw stream operations.  They do not own a printf count. */
size_t __kfile_write(FILE *f, const void *buf, size_t len);
size_t __kfile_repeat(FILE *f, unsigned char ch, size_t len);

/* Tiny lock hooks kept private to KLIB.  They are no-ops until threading exists. */
void __kfile_lock(FILE *f);
void __kfile_unlock(FILE *f);
void __kfile_register(FILE *f);
void __kfile_unregister(FILE *f);
int __kfile_has_open_streams(void);
void __kfile_close_all(void);
size_t __kfile_stdin_read(FILE *f, unsigned char *buf, size_t len);

#ifdef __cplusplus
}
#endif

#endif

#endif
