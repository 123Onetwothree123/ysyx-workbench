#include <am.h>
#include <klib-macros.h>
#include <klib.h>
extern char _heap_start, _heap_end;
Area heap = RANGE(&_heap_start, &_heap_end);
static const char mainargs[MAINARGS_MAX_LEN] = TOSTRING(MAINARGS_PLACEHOLDER);

void putch(char ch) { asm volatile("csrw 0x8a0, %0" : : "r"((int)ch)); }
void halt(int code) { asm volatile("mv a0, %0; ebreak" : : "r"(code)); while (1); }

void _trm_init() { extern int main(const char*); cte_init(NULL); int r = main(mainargs); halt(r); }
