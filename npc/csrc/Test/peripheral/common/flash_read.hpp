#ifndef PERIPHERAL_COMMON_FLASH_READ_HPP
#define PERIPHERAL_COMMON_FLASH_READ_HPP

#include "flash_map.hpp"
#include <cstdint>

inline constexpr std::uint32_t bit_reverse_24(std::uint32_t x)
{
    std::uint32_t r{};
    for (int i{0}; i < 24; i++)
    {
        if (x & (1u << i))
        {
            r |= 1u << (23 - i);
        }
    }
    return r;
}

inline constexpr std::uint32_t swap_bits_bytes(std::uint32_t x)
{
    x = ((x & 0x55555555u) << 1) | ((x >> 1) & 0x55555555u);
    x = ((x & 0x33333333u) << 2) | ((x >> 2) & 0x33333333u);
    x = ((x & 0x0F0F0F0Fu) << 4) | ((x >> 4) & 0x0F0F0F0Fu);
    x = ((x & 0x00FF00FFu) << 8) | ((x >> 8) & 0x00FF00FFu);
    x = (x << 16) | (x >> 16);
    return ((x & 0xFFu) << 24) | ((x & 0xFF00u) << 8) |
           ((x >> 8) & 0xFF00u) | ((x >> 24) & 0xFFu);
}

inline std::uint32_t flash_read(std::uint32_t addr)
{
    volatile auto *spi{reinterpret_cast<volatile std::uint32_t *>(SPI_BASE)};
    spi[SPI_DIVIDER] = 1;
    spi[SPI_SS] = 0;        // 复位flash状态机
    spi[SPI_SS] = FLASH_SS; // 选中flash
    spi[SPI_CTRL] = CTRL_CHAR_LEN | CTRL_TX_NEG | CTRL_LSB;
    spi[SPI_TX_0] = (bit_reverse_24(addr) << 8) | FLASH_CMD;
    spi[SPI_CTRL] = CTRL_CHAR_LEN | CTRL_GO | CTRL_TX_NEG | CTRL_LSB;
    while (spi[SPI_CTRL] & CTRL_GO)
    {
    }
    return swap_bits_bytes(spi[SPI_RX_1]);
}

#endif
