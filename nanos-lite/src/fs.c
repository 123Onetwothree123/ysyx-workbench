#include <fs.h>

typedef size_t (*ReadFn)(void *buf, size_t offset, size_t len);
typedef size_t (*WriteFn)(const void *buf, size_t offset, size_t len);

typedef struct {
  char *name;
  size_t size;
  size_t disk_offset;
  ReadFn read;
  WriteFn write;
  size_t open_offset;
} Finfo;

enum {FD_STDIN, FD_STDOUT, FD_STDERR, FD_EVENTS, FD_FB, FD_DISPINFO, FD_SBCTL, FD_SB};

size_t serial_write(const void *buf, size_t offset, size_t len);
size_t events_read(void *buf, size_t offset, size_t len);
size_t dispinfo_read(void *buf, size_t offset, size_t len);
size_t fb_write(const void *buf, size_t offset, size_t len);
size_t sbctl_read(void *buf, size_t offset, size_t len);
size_t sbctl_write(const void *buf, size_t offset, size_t len);
size_t sb_write(const void *buf, size_t offset, size_t len);

size_t invalid_read(void *buf, size_t offset, size_t len) {
  panic("should not reach here");
  return 0;
}

size_t invalid_write(const void *buf, size_t offset, size_t len) {
  panic("should not reach here");
  return 0;
}

/* This is the information about all files in disk. */
static Finfo file_table[] __attribute__((used)) = {
  [FD_STDIN]  = {"stdin", 0, 0, invalid_read, invalid_write},
  [FD_STDOUT] = {"stdout", 0, 0, invalid_read, serial_write},
  [FD_STDERR] = {"stderr", 0, 0, invalid_read, serial_write},
  [FD_EVENTS] = {"/dev/events", 0, 0, events_read, NULL},
  [FD_FB] = {"/dev/fb", 0, 0, NULL, fb_write},
  [FD_DISPINFO] = {"/proc/dispinfo", 0, 0, dispinfo_read, NULL},
  [FD_SBCTL] = {"/dev/sbctl", 0, 0, sbctl_read, sbctl_write},
  [FD_SB] = {"/dev/sb", 0, 0, NULL, sb_write},
#include "files.h"
};

#define NR_FILES (sizeof(file_table) / sizeof(file_table[0]))

size_t ramdisk_read(void *buf, size_t offset, size_t len);
size_t ramdisk_write(const void *buf, size_t offset, size_t len);

int fs_open(const char *pathname, int flags, int mode) {
  (void)flags;
  (void)mode;

  for (size_t i = 0; i < NR_FILES; i++) {
    if (strcmp(file_table[i].name, pathname) == 0) {
      file_table[i].open_offset = 0;
      return (int)i;
    }
  }

  panic("file not found: %s", pathname);
  return -1;
}

size_t fs_read(int fd, void *buf, size_t len) {
  assert(fd >= 0 && (size_t)fd < NR_FILES);
  Finfo *f = &file_table[fd];

  if (fd == FD_STDIN || fd == FD_STDOUT || fd == FD_STDERR) {
    return 0;
  }
  if (f->read != NULL) {
    return f->read(buf, f->open_offset, len);
  }

  if (f->open_offset >= f->size) {
    return 0;
  }
  size_t n = f->size - f->open_offset;
  if (n > len) {
    n = len;
  }
  size_t ret = ramdisk_read(buf, f->disk_offset + f->open_offset, n);
  f->open_offset += ret;
  return ret;
}

size_t fs_write(int fd, const void *buf, size_t len) {
  assert(fd >= 0 && (size_t)fd < NR_FILES);

  if (fd == FD_STDIN) {
    return 0;
  }

  Finfo *f = &file_table[fd];
  if (f->write != NULL) {
    return f->write(buf, f->open_offset, len);
  }

  if (f->open_offset >= f->size) {
    return 0;
  }
  size_t n = f->size - f->open_offset;
  if (n > len) {
    n = len;
  }
  size_t ret = ramdisk_write(buf, f->disk_offset + f->open_offset, n);
  f->open_offset += ret;
  return ret;
}

intptr_t fs_lseek(int fd, intptr_t offset, int whence) {
  assert(fd >= 0 && (size_t)fd < NR_FILES);

  if (fd == FD_STDIN || fd == FD_STDOUT || fd == FD_STDERR) {
    return 0;
  }

  Finfo *f = &file_table[fd];
  intptr_t base;
  switch (whence) {
    case SEEK_SET: base = 0; break;
    case SEEK_CUR: base = (intptr_t)f->open_offset; break;
    case SEEK_END: base = (intptr_t)f->size; break;
    default: return -1;
  }

  intptr_t new_offset = base + offset;
  if (new_offset < 0 || (size_t)new_offset > f->size) {
    return -1;
  }

  f->open_offset = (size_t)new_offset;
  return new_offset;
}

int fs_close(int fd) {
  assert(fd >= 0 && (size_t)fd < NR_FILES);
  return 0;
}

void init_fs() {
  AM_GPU_CONFIG_T gpu;
  ioe_read(AM_GPU_CONFIG, &gpu);
  file_table[FD_FB].size = (size_t)gpu.width * gpu.height * 4;
}
