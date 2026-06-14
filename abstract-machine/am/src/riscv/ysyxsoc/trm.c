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
    volatile char *serial_port = (volatile char *)0x10000000;
    *serial_port = ch;
}
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