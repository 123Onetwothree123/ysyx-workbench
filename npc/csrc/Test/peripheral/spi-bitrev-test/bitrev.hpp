#ifndef BITREV_HPP
#define BITREV_HPP
#define BITREV_BASE 0x10001000L
#define SPI_TX_0 0x0
#define SPI_RX_0 0x0
#define SPI_CTRL 0x4
#define SPI_DIVIDER 0x5
#define SPI_SS 0x6
#define CTRL_CHAR_LEN 0xF
#define CTRL_GO (1 << 8)
#define CTRL_RX_NEG (1 << 9)
#define CTRL_TX_NEG (1 << 10)
#define CTRL_LSB (1 << 11)
#define CTRL_IE (1 << 12)
#define CTRL_ASS (1 << 13)
#define BITREV_SS (1 << 7)
#endif