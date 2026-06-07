#include <am.h>
#include <klib-macros.h>
#include <klib.h>

extern char _heap_start;
int main(const char *args);

extern char _pmem_start;
#define PMEM_SIZE (128 * 1024 * 1024)
#define PMEM_END ((uintptr_t)&_pmem_start + PMEM_SIZE)

Area heap = RANGE(&_heap_start, PMEM_END);
static const char mainargs[MAINARGS_MAX_LEN] = TOSTRING(MAINARGS_PLACEHOLDER); // defined in CFLAGS

void putch(char ch)
{
  // 他妈的搞了半天这玩意居然是空实现，调了半天为什么npc跑hello没输出hello，然后hit good trap
  volatile char *serial_port = (volatile char *)0x10000000;
  *serial_port = ch;
}
//抄NEMU的
void halt(int code)
{
  asm volatile("mv a0, %0; ebreak" : : "r"(code));
  while (1)
    ;
}

void _trm_init()
{
  int ret = main(mainargs);
  halt(ret);
}
