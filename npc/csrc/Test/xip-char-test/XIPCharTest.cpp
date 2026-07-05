#include "XIPCharTest.hpp"
#include <cstdint>
#include <klib-macros.h>
#include <klib.h>
int main(const char *args)
{
    volatile std::uint32_t *flash{reinterpret_cast<volatile std::uint32_t *>(FLASH_XIP_BASE)};
    volatile std::uint32_t *sram{reinterpret_cast<volatile std::uint32_t *>(SRAM_BASE)};
    for (std::uint32_t i{0}; i < CHAR_TEST_SIZE; i += 4)
    {
        sram[i / 4] = flash[i / 4];
    }
    asm volatile("jr %0" : : "r"(SRAM_BASE));
    return 0;
}
