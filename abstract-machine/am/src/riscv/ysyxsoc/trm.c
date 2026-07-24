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
    volatile char *tx = (volatile char *)0xa0001380;
    *tx = ch;
}
void halt(int code)
{
    asm volatile("mv a0, %0; ebreak" : : "r"(code));
    while (1)
        ;
}
static int __pad[128];
void _trm_init()
{
    for (int i = 0; i < 128; i++) __pad[i] = 0;
    volatile char *tx = (volatile char *)0xa0002000;
    *tx = 'X';
    asm volatile("li a0, 0; ebreak");
}
