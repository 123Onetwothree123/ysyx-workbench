#include <am.h>
#include <klib-macros.h>
#include <klib.h>

extern char _heap_start;
int main(const char *args);

extern char _pmem_start;
// direct-NPC的RAM窗口跟随Kconfig，缺省值保持0x80000000和64MiB
#ifndef AM_NPC_RAM_SIZE
#define AM_NPC_RAM_SIZE (64 * 1024 * 1024)
#endif
#ifndef AM_PMA_UART_BASE
#define AM_PMA_UART_BASE 0x10000000
#endif
#define PMEM_SIZE AM_NPC_RAM_SIZE
#define PMEM_END ((uintptr_t)&_pmem_start + PMEM_SIZE)

Area heap = RANGE(&_heap_start, PMEM_END);
static const char mainargs[MAINARGS_MAX_LEN] = TOSTRING(MAINARGS_PLACEHOLDER); // defined in CFLAGS

void putch(char ch)
{
  // 他妈的搞了半天这玩意居然是空实现，调了半天为什么npc跑hello没输出hello，然后hit good trap
  volatile char *serial_port = (volatile char *)(uintptr_t)AM_PMA_UART_BASE;
  *serial_port = ch;
}
//抄NEMU的
void halt(int code)
{
  // custom-0 是 NPC 仿真退出 ABI；标准 EBREAK 必须留给 breakpoint
  // 异常处理程序。a0 仍沿用 AM 的退出码约定。
  asm volatile("mv a0, %0; .word 0x0000000b" : : "r"(code) : "a0", "memory");
  while (1)
    ;
}

void _trm_init()
{
  __klib_init_array();
  exit(main(mainargs));
}
