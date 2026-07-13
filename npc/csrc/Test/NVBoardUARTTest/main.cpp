#include <am.h>

int main(const char *)
{
    volatile unsigned char *LSR = (volatile unsigned char *)0x10000005;
    volatile unsigned int *RBR = (volatile unsigned int *)0x10000000;
    for (const char *p{"UART RX Test\n"}; *p; ++p) putch(*p);
    while (1)
    {
        if (*LSR & 0x01)
        {
            char Ch = (char)(*RBR & 0xff);
            putch('<');
            putch(Ch);
            putch('>');
        }
    }
    return 0;
}
