#include <am.h>
#include <klib.h>
#include <klib-macros.h>

#ifndef __ISA_NATIVE__

void *__dso_handle = &__dso_handle;

int __cxa_guard_acquire(unsigned long long *guard) {
  unsigned char *bytes = (unsigned char *)guard;
  if ((bytes[0] & 1u) != 0) return 0;
  if (bytes[1] != 0) panic("recursive static initialization");
  bytes[1] = 1;
  return 1;
}

void __cxa_guard_release(unsigned long long *guard) {
  unsigned char *bytes = (unsigned char *)guard;
  bytes[0] = 1;
  bytes[1] = 0;
}

void __cxa_guard_abort(unsigned long long *guard) {
  unsigned char *bytes = (unsigned char *)guard;
  bytes[1] = 0;
}

#endif
