#include <am.h>
#include <klib-macros.h>

int main(const char *)
{
    for (const char *p{"UART Echo Test\n"}; *p; ++p)
    {
        putch(*p);
    }
    while (true)
    {
        char Ch{static_cast<char>(io_read(AM_UART_RX).data)};
        if (Ch != static_cast<char>(0xff))
        {
            putch(Ch);
            if (Ch == '\r')
            {
                putch('\n');
            }
        }
    }
    return 0;
}
