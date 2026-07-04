#include <klib-macros.h>
#include <klib.h>
#include "bitrev.hpp"
#include <cstdint>
int main(const char *args)
{
    volatile std::uint32_t *spi = (volatile std::uint32_t *)BITREV_BASE;
    spi[SPI_DIVIDER] = 1;
    spi[SPI_SS] = BITREV_SS;
    spi[SPI_TX_0] = 0xC2;
    spi[SPI_CTRL] = CTRL_CHAR_LEN | CTRL_GO | CTRL_TX_NEG | CTRL_LSB;
    // 等硬件自动清零
    while (spi[SPI_CTRL] & CTRL_GO)
        ;
    std::uint32_t rx = (spi[SPI_RX_0] >> 8) & 0xFF;
    if (rx == 0x43)
    {
        putstr("PASS\n");
    }
    else
    {
        putstr("FAIL\n");
    }
    return 0;
}