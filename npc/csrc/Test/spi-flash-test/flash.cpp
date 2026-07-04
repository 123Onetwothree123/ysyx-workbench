#include "flash.hpp"
#include <klib-macros.h>
#include <klib.h>
std::uint32_t flash_read(std::uint32_t addr)
{
    volatile auto *spi = reinterpret_cast<volatile uint32_t *>(SPI_BASE);
    spi[SPI_DIVIDER] = 1;
    spi[SPI_SS] = FLASH_SS;
    spi[SPI_TX_0] = (addr << 8) | FLASH_CMD;
    spi[SPI_TX_0] = (addr << 8) | FLASH_CMD;
    spi[SPI_CTRL] = CTRL_CHAR_LEN | CTRL_GO | CTRL_TX_NEG | CTRL_LSB;
    while (spi[SPI_CTRL] & CTRL_GO)
        ;
    return spi[SPI_RX_0];
}
int main(const char *args)
{
    int32_t errors{0};
    for (std::uint32_t addr{0}; addr < 256; addr += 4)
    {
        auto got{flash_read(addr)};
        auto expect = static_cast<std::uint32_t>((addr + 3) << 24 | (addr + 2) << 16 | (addr + 1) << 8 | addr);
        if (got != expect)
        {
            errors++;
        }
    }
    return 0;
}