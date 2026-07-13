#include <am.h>

static char UARTGetChar()
{
    volatile unsigned char *const LSR{reinterpret_cast<volatile unsigned char *>(0x10000005U)};
    volatile unsigned char *const RBR{reinterpret_cast<volatile unsigned char *>(0x10000000U)};
    while (!(*LSR & 0x01))
    {
    }
    return static_cast<char>(*RBR);
}

int main(const char *)
{
    while (true)
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
