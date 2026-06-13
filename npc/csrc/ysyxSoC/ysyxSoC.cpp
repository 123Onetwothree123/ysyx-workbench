#include "ysyxSoC.hpp"
#include <cassert>
extern "C" void flash_read(int32_t addr, int32_t *data)
{
    assert(0);
}
extern "C" void mrom_read(int32_t addr, int32_t *data)
{
    // assert(0);
    (void)addr;
    assert(data != nullptr);
    *data = 0x00100073; // ebreak
}