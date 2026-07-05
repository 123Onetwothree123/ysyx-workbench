#include <klib-macros.h>
#include <klib.h>
int main(const char *args)
{
    for (const char *p = "XIP PASS\n"; *p; p++) {
        *(volatile char *)0x10000000L = *p;
    }
    while (1);
    return 0;
}