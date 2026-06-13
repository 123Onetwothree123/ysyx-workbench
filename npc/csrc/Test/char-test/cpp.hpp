#ifndef CPP_HPP
#define CPP_HPP
#define UART_BASE 0x100000000L
#define UART_TX   0
void _start() {
  *(volatile char *)(UART_BASE + UART_TX) = 'A';
  *(volatile char *)(UART_BASE + UART_TX) = '\n';
  while (1);
}
#endif