#include "ysyxSoC.hpp"
#include <cassert>
#include <vector>
#include <print>
std::vector<uint8_t> mrom;
extern "C" void flash_read(int32_t addr, int32_t *data)
{
    assert(0);
}
extern "C" void mrom_read(int32_t addr, int32_t *data)
{
    assert(data != nullptr);
    uint32_t offset{static_cast<uint32_t>(addr) - 0x20000000u}; // 脑残编译器，这里auto会解读成ui32，他妈的那每次auto写了后，还得检查一遍，这和没写auto有什么区别，他妈的
    if (offset + 3 >= mrom.size())
    {
        std::println("超C++的mrom的范围了");
        *data = 0;
        return;
    }
    *data = static_cast<int32_t>(static_cast<uint32_t>(mrom[offset]) | (static_cast<uint32_t>(mrom[offset + 1]) << 8) | (static_cast<uint32_t>(mrom[offset + 2]) << 16) | (static_cast<uint32_t>(mrom[offset + 3]) << 24));
}