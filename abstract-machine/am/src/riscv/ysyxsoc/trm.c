#include <am.h>
#include <klib-macros.h>
#include <klib.h>
extern char _heap_start, _heap_end;
Area heap = RANGE(&_heap_start, &_heap_end);
static const char mainargs[MAINARGS_MAX_LEN] __attribute__((used)) = TOSTRING(MAINARGS_PLACEHOLDER);
extern void __am_asm_trap(void);

void putch(char ch) {
  volatile unsigned char *THR = (volatile unsigned char *)0x10000000;
  volatile unsigned char *LSR = (volatile unsigned char *)0x10000005;
  while (!(*LSR & 0x20));  // wait for THRE (Transmitter Holding Register Empty)
  *THR = ch;
}
void halt(int code) {
  // NPC/ysyxSoC 仿真退出使用 custom-0；标准 EBREAK 继续产生
  // breakpoint 异常并进入 mtvec。
  asm volatile("mv a0, %0; .word 0x0000000b" : : "r"(code) : "a0", "memory");
  while (1);
}

__attribute__((naked))
void _trm_init()
{
    asm volatile(
        "csrw 0x305, %0\n"
        "addi sp, sp, -16\n"
        "sw ra, 12(sp)\n"
        "li a0, 0\n"
        "call cte_init\n"
        "call __klib_init_array\n"
        "la a0, mainargs\n"
        "call main\n"
        "call exit\n"
        :
        : "r"(&__am_asm_trap)
    );
}
