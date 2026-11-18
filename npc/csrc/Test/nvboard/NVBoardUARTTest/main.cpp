#include <am.h>

static char UARTGetChar()
{
    volatile unsigned char *LSR{reinterpret_cast<volatile unsigned char *>(0x10000005)};
    volatile unsigned char *RBR{reinterpret_cast<volatile unsigned char *>(0x10000000)};
    while (!(*LSR & 0x01))
    {
    }
    return static_cast<char>(*RBR);
}

int main(const char *)
{
    for (const char *p{"UART RX Test\n"}; *p; ++p)
    {
        putch(*p);
    }
    while (1)
    {
        char Ch{UARTGetChar()};
        putch(Ch);
        if (Ch == '\r')
        {
            putch('\n');
        }
    }
    return 0;
}
