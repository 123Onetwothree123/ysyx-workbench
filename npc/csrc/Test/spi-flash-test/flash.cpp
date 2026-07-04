#include "flash.hpp"
#include <klib-macros.h>
#include <klib.h>

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
    spi[SPI_SS] = 0;           // SS高→flash复位
    spi[SPI_SS] = FLASH_SS;    // 重新选中flash
    spi[SPI_CTRL] = CTRL_CHAR_LEN | CTRL_TX_NEG | CTRL_LSB;
    spi[SPI_TX_0] = (addr << 8) | bitrev(FLASH_CMD);
    spi[SPI_CTRL] = CTRL_CHAR_LEN | CTRL_GO | CTRL_TX_NEG | CTRL_LSB;
    while (spi[SPI_CTRL] & CTRL_GO);

    auto raw{spi[SPI_RX_1]};
    std::uint32_t x = raw;
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
    auto v0 = flash_read(0); auto e0 = 0x03020100u;
    auto v4 = flash_read(4); auto e4 = 0x07060504u;
    auto v8 = flash_read(8); auto e8 = 0x0B0A0908u;
    volatile char *tx = (volatile char *)0x10000000;
    volatile char *lsr = (volatile char *)0x10000005;
    auto pc = [&](char c) { while (!(*lsr & 0x20)); *tx = c; };
    auto pr = [&](std::uint32_t v) {
        pc('0'); pc('x');
        for (int i = 28; i >= 0; i -= 4) { int d = (v >> i) & 0xF; pc(d < 10 ? '0' + d : 'a' + d - 10); }
    };
    pc('0'); pr(v0); pc(' '); pr(e0); pc(' ');
    pc('4'); pr(v4); pc(' '); pr(e4); pc(' ');
    pc('8'); pr(v8); pc(' '); pr(e8); pc('\n');
    
    int32_t errors{0};
    for (std::uint32_t addr{0}; addr < 256; addr += 4)
    {
        auto got{flash_read(addr)};
        auto expect = static_cast<std::uint32_t>((addr + 3) << 24 | (addr + 2) << 16 | (addr + 1) << 8 | addr);
        if (got != expect) errors++;
    }
    if (errors == 0) putstr("PASS\n"); else putstr("FAIL\n");
    return 0;
}
