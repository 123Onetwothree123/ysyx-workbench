#include <am.h>

static char UARTGetChar()
{
    volatile unsigned char *LSR = (volatile unsigned char *)0x10000005;
    volatile unsigned char *RBR = (volatile unsigned char *)0x10000000;
    while (!(*LSR & 0x01))
    {
    }
    return (char)*RBR;
}

int main(const char *)
{
    for (const char *p{"UART RX Test\n"}; *p; ++p) putch(*p);
    while (1)
    {
        char Ch = UARTGetChar();
        putch(Ch);
        if (Ch == '\r') putch('\n');
    }
    return 0;
}
