#include <am.h>
#include <klib-macros.h>

int main(const char *)
{
    for (const char *p{"UART NonBlock Test\n"}; *p; ++p) putch(*p);
    while (1)
    {
        char Ch = io_read(AM_UART_RX).data;
        if (Ch != (char)-1)
        {
            putch('<');
            putch(Ch);
            putch('>');
        }
    }
    return 0;
}
