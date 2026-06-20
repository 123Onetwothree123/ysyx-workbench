module;
module npc.ysyxSoC;
#ifndef CONFIG_MBASE
#define CONFIG_MBASE 0x20000000u
#endif
std::vector<std::uint8_t> mrom;
extern "C" void flash_read(std::int32_t addr, std::int32_t *data)
{
    std::unreachable();
}
extern "C" void mrom_read(std::int32_t addr, std::int32_t *data)
{
    std::uint32_t offset{static_cast<std::uint32_t>(addr) - static_cast<std::uint32_t>(CONFIG_MBASE)}; // 脑残编译器，这里auto会解读成ui32，他妈的那每次auto写了后，还得检查一遍，这和没写auto有什么区别，他妈的
    if (offset + 3 >= mrom.size())
    {
        std::println("超C++的mrom的范围了");
        *data = 0;
        return;
    }
    *data = static_cast<std::int32_t>(static_cast<std::uint32_t>(mrom[offset]) | (static_cast<std::uint32_t>(mrom[offset + 1]) << 8) | (static_cast<std::uint32_t>(mrom[offset + 2]) << 16) | (static_cast<std::uint32_t>(mrom[offset + 3]) << 24));
}
