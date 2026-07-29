#include <cpu/btrace.h>
#include <stdio.h>

void btrace_write(vaddr_t pc, vaddr_t target, bool taken) {
    printf("%08x %08x %d\n", pc, target, taken ? 1 : 0);
}