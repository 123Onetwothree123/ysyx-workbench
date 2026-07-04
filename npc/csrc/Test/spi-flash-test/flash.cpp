#include "flash.hpp"
#include <klib-macros.h>
#include <klib.h>
// 位翻转，他妈的flash模型会逆序接收
constexpr std::uint8_t bitrev(std::uint8_t b)
{
    b = ((b & 0xF0) >> 4) | ((b & 0x0F) << 4);
    b = ((b & 0xCC) >> 2) | ((b & 0x33) << 2);
    b = ((b & 0xAA) >> 1) | ((b & 0x55) << 1);
    return b;
}
std::uint32_t flash_read(std::uint32_t addr)
{
    volatile auto *spi{reinterpret_cast<volatile uint32_t *>(SPI_BASE)};
    spi[SPI_DIVIDER] = 1;
    spi[SPI_SS] = FLASH_SS;
    spi[SPI_CTRL] = CTRL_CHAR_LEN | CTRL_TX_NEG | CTRL_LSB;
    spi[SPI_TX_0] = (addr << 8) | bitrev(FLASH_CMD);
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