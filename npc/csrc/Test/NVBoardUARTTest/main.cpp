#include <am.h>
#include <klib-macros.h>

int main(const char *)
{
    for (const char *p{"UART NB v4\n"}; *p; ++p) putch(*p);
    while (1)
    {
        for (int t = 0; t < 100; t++)
        {
            char Ch = io_read(AM_UART_RX).data;
            if (Ch != (char)-1)
            {
                putch('<');
                putch(Ch);
                putch('>');
                break;
            }
            for (int i = 0; i < 5000; i++) asm volatile("" : "+r"(i));
        }
    }
    return 0;
}
