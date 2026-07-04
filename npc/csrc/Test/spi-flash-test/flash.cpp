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
    spi[SPI_SS] = 0;         // 拉高SS，复位flash状态机
    for(volatile int x=0;x<100;x++);  // 等几个周期
    spi[SPI_SS] = FLASH_SS;  // 重新选中flash
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
    std::uint32_t addrs[] = {0u, 4u};
    for (auto addr : addrs) {
        auto got{flash_read(addr)};
        auto expect = static_cast<std::uint32_t>((addr + 3) << 24 | (addr + 2) << 16 | (addr + 1) << 8 | addr);
        volatile char *ltx = (volatile char *)0x10000000;
        volatile char *llsr = (volatile char *)0x10000005;
        auto pc = [&](char c) { while (!(*llsr & 0x20)); *ltx = c; };
        auto pr = [&](std::uint32_t v) {
            pc('0'); pc('x');
            for (int i = 28; i >= 0; i -= 4) { int d = (v >> i) & 0xF; pc(d < 10 ? '0' + d : 'a' + d - 10); }
        };
        putstr("a="); pr(addr); putstr(" g="); pr(got); putstr(" e="); pr(expect); pc('\n');
    }
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