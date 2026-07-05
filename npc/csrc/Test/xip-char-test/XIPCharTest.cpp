#include <klib-macros.h>
#include <klib.h>
int main(const char *args)
{
    *(volatile char *)0x10000000L = 'A';
    while(1);
    return 0;
}