#include "flash.hpp"
#include <klib-macros.h>
#include <klib.h>

std::uint32_t flash_read(std::uint32_t addr)
{
    volatile auto *spi{reinterpret_cast<volatile uint32_t *>(SPI_BASE)};

    std::uint32_t raddr = 0;
    for (int i = 0; i < 24; i++)
        if (addr & (1u << i))
            raddr |= 1u << (23 - i);

    spi[SPI_DIVIDER] = 1;
    spi[SPI_SS] = 0;          // SS高→flash复位回cmd_t
    spi[SPI_SS] = FLASH_SS;   // 重新选中flash
    spi[SPI_CTRL] = CTRL_CHAR_LEN | CTRL_TX_NEG | CTRL_LSB;
    spi[SPI_TX_0] = (raddr << 8) | FLASH_CMD;
    spi[SPI_CTRL] = CTRL_CHAR_LEN | CTRL_GO | CTRL_TX_NEG | CTRL_LSB;
    while (spi[SPI_CTRL] & CTRL_GO);

    std::uint32_t x = spi[SPI_RX_1];
    x = ((x & 0x55555555u) << 1) | ((x >> 1) & 0x55555555u);
    x = ((x & 0x33333333u) << 2) | ((x >> 2) & 0x33333333u);
    x = ((x & 0x0F0F0F0Fu) << 4) | ((x >> 4) & 0x0F0F0F0Fu);
    x = ((x & 0x00FF00FFu) << 8) | ((x >> 8) & 0x00FF00FFu);
    x = (x << 16) | (x >> 16);
    return ((x & 0xFFu) << 24) | ((x & 0xFF00u) << 8) |
           ((x >> 8) & 0xFF00u) | ((x >> 24) & 0xFFu);
}

int main(const char *args)
{
    int errors = 0;
    for (std::uint32_t a = 0; a < 256; a += 4)
    {
        auto got = flash_read(a);
        auto exp = static_cast<std::uint32_t>((a+3)<<24 | (a+2)<<16 | (a+1)<<8 | a);
        if (got != exp) errors++;
    }
    if (errors == 0) putstr("PASS\n");
    else putstr("FAIL\n");
    return 0;
}
