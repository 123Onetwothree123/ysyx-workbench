#include <am.h>

void __am_uart_rx(AM_UART_RX_T *rx)
{
    volatile unsigned char *LSR = (volatile unsigned char *)0x10000005;
    volatile unsigned char *RBR = (volatile unsigned char *)0x10000000;
    if (*LSR & 0x01)
    {
        rx->data = *RBR;
    }
    else
    {
        rx->data = 0xff;
    }
}
