#include "flash_char_test.hpp"
#include <am.h>
#include <klib-macros.h>
#include <klib.h>

#define SRAM_BASE      0x0f000000L
#define FLASH_OFFSET   0
#define CHAR_TEST_SIZE 16

int main(const char *args)
{
    volatile std::uint32_t *sram{reinterpret_cast<volatile std::uint32_t *>(SRAM_BASE)};

    // 从 flash 读 char-test 到 SRAM
    for (std::uint32_t i{0}; i < CHAR_TEST_SIZE; i += 4)
    {
        sram[i / 4] = flash_read(FLASH_OFFSET + i);
    }

    // 跳转到 SRAM 执行 char-test
    asm volatile("jr %0" : : "r"(SRAM_BASE));
}
