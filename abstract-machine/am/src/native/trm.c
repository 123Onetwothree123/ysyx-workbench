#include <am.h>
#include <stdio.h>
#include <unistd.h>
#include <klib-macros.h>

void __am_platform_dummy();
void __am_exit_platform(int code);

void trm_init() {
  __am_platform_dummy();
}

void putch(char ch) {
#ifdef __NATIVE_USE_KLIB__
  (void)write(STDOUT_FILENO, &ch, 1);
#else
  putchar(ch);
#endif
}

void halt(int code) {
  const char *fmt = "Exit code = 40h\n";
  for (const char *p = fmt; *p; p++) {
    char ch = *p;
    if (ch == '0' || ch == '4') {
      ch = "0123456789abcdef"[(code >> (ch - '0')) & 0xf];
    }
    putch(ch);
  }
  __am_exit_platform(code);
  putstr("Should not reach here!\n");
  while (1);
}

Area heap = {};
