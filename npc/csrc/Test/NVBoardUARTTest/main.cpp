#include <am.h>
#include <klib-macros.h>

int main(const char *)
{
    for (const char *p{"UART NB Test v3\n"}; *p; ++p) putch(*p);
    while (1)
    {
        char Ch = io_read(AM_UART_RX).data;
        if (Ch != (char)-1)
        {
            putch('<');
            putch(Ch);
            putch('>');
        }
        else
        {
            for (int i = 0; i < 200; i++) asm volatile("" : "+r"(i));
        }
    }
    return 0;
}
