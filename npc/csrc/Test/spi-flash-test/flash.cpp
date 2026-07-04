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
    volatile char *tx = (volatile char *)0x10000000;
    volatile char *lsr = (volatile char *)0x10000005;
    auto pc = [&](char c) { while (!(*lsr & 0x20)); *tx = c; };
    auto ph = [&](unsigned d) { pc(d<10?'0'+d:'a'+d-10); };
    auto pr = [&](std::uint32_t v) {
        for (int i = 28; i >= 0; i -= 4) ph((v>>i)&0xF);
    };
    pc('0'); pr(v0); pc(' '); pr(e0); pc(' ');
    pc('4'); pr(v4); pc(' '); pr(e4); pc('\n');

    int errors = 0;
    for (std::uint32_t a = 0; a < 256; a += 4)
    {
        auto got = flash_read(a);
        auto exp = static_cast<std::uint32_t>((a+3)<<24 | (a+2)<<16 | (a+1)<<8 | a);
        if (got != exp) errors++;
    }
    if (errors == 0) {putstr("PASS\n");}
    else {putstr("FAIL\n");}
    return 0;
}