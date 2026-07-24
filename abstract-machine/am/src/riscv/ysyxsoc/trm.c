#include <am.h>
#include <klib-macros.h>
#include <klib.h>
extern char _heap_start;
extern char _heap_end;
int main(const char *args);
Area heap = RANGE(&_heap_start, &_heap_end);
static const char mainargs[MAINARGS_MAX_LEN] = TOSTRING(MAINARGS_PLACEHOLDER);
void putch(char ch)
{
    volatile char *tx = (volatile char *)0x10000000;
    volatile char *lsr = (volatile char *)0x10000005;
    while (!(*lsr & 0x20))
        ;
    *tx = ch;
}
void halt(int code)
{
    asm volatile("mv a0, %0; ebreak" : : "r"(code));
    while (1)
        ;
}
void _trm_init()
{
    // 给UART16550初始化
    volatile char *lcr = (volatile char *)0x10000003;
    volatile char *dll = (volatile char *)0x10000000;
    volatile char *dlh = (volatile char *)0x10000001;
    *lcr = 0x80; // DLAB=1, 准备写除数
    *dll = 0x01; // 除数低字节 = 1
    *dlh = 0x00; // 除数高字节 = 0
    *lcr = 0x03; // DLAB=0, 8N1
    cte_init(NULL); // 至少设置 mtvec，异常时不跑飞
    int ret = main(mainargs);
    halt(ret);
}