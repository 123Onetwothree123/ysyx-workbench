#include "flash.hpp"
#include <klib-macros.h>
#include <klib.h>

std::uint32_t flash_read(std::uint32_t addr)
{
    volatile auto *spi{reinterpret_cast<volatile uint32_t *>(SPI_BASE)};

    // 地址 24-bit 翻转: TX[8]=addr[23], TX[9]=addr[22], ...
    std::uint32_t raddr = 0;
    for (int i = 0; i < 24; i++)
        if (addr & (1u << i))
            raddr |= 1u << (23 - i);

    spi[SPI_DIVIDER] = 1;
    spi[SPI_SS] = FLASH_SS;
    spi[SPI_CTRL] = CTRL_CHAR_LEN | CTRL_TX_NEG | CTRL_LSB;  // 先加载cnt
    spi[SPI_TX_0] = (raddr << 8) | FLASH_CMD;
    spi[SPI_CTRL] = CTRL_CHAR_LEN | CTRL_GO | CTRL_TX_NEG | CTRL_LSB;
    while (spi[SPI_CTRL] & CTRL_GO);

    // 数据在 RX_1(bits 63:32), 已被bit翻转+byte翻转, 还原
    std::uint32_t x = spi[SPI_RX_1];
    std::uint32_t y = x;
    y = ((y & 0x55555555u) << 1) | ((y >> 1) & 0x55555555u);
    y = ((y & 0x33333333u) << 2) | ((y >> 2) & 0x33333333u);
    y = ((y & 0x0F0F0F0Fu) << 4) | ((y >> 4) & 0x0F0F0F0Fu);
    y = ((y & 0x00FF00FFu) << 8) | ((y >> 8) & 0x00FF00FFu);
    y = (y << 16) | (y >> 16);
    // debug: print raw + result
    volatile char *tx = (volatile char *)0x10000000;
    volatile char *lsr = (volatile char *)0x10000005;
    auto pc = [&](char c) { while (!(*lsr & 0x20)); *tx = c; };
    auto ph = [&](unsigned d) { pc(d<10?'0'+d:'a'+d-10); };
    auto pr = [&](std::uint32_t v) {
        for (int i = 28; i >= 0; i -= 4) ph((v>>i)&0xF);
    };
    if (addr < 8) { pc('R'); pr(x); pc('>'); pr(((y & 0xFFu) << 24) | ((y & 0xFF00u) << 8) | ((y >> 8) & 0xFF00u) | ((y >> 24) & 0xFFu)); pc(' '); }
    return ((y & 0xFFu) << 24) | ((y & 0xFF00u) << 8) |
           ((y >> 8) & 0xFF00u) | ((y >> 24) & 0xFFu);
}
int main(const char *args)
{
    auto v0 = flash_read(0); auto e0 = 0x03020100u;
    auto v4 = flash_read(4); auto e4 = 0x07060504u;
    volatile char *tx = (volatile char *)0x10000000;
    volatile char *lsr = (volatile char *)0x10000005;
    auto pc = [&](char c) { while (!(*lsr & 0x20)); *tx = c; };
    auto ph = [&](unsigned d) { pc(d<10?'0'+d:'a'+d-10); };
    auto pr = [&](std::uint32_t v) {
        for (int i = 28; i >= 0; i -= 4) ph((v>>i)&0xF);
    };
    pc('0'); pr(v0); pc(' '); pr(e0); pc('|');
    pc('4'); pr(v4); pc(' '); pr(e4); pc('\n');
    return 0;
}