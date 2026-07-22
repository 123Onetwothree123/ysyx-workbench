module;
module npc.ysyxSoC;
#ifndef CONFIG_MBASE
#define CONFIG_MBASE 0x20000000u
#endif
std::vector<std::uint8_t> mrom;
std::vector<std::uint8_t> FlashMemory;
extern "C" void flash_read(std::int32_t addr, std::int32_t *data)
{
    std::uint32_t offset{static_cast<std::uint32_t>(addr)}; // 已经是偏移，不用减基址
    std::uint32_t aligned{offset & ~3u};
    std::uint32_t result{0};
    for (int i = 0; i < 4; i++)
    {
        if (aligned + i < FlashMemory.size())
        {
            result |= static_cast<std::uint32_t>(FlashMemory[aligned + i]) << (8 * i);
        }
    }
    *data = static_cast<std::int32_t>(result);
}
extern "C" void mrom_read(std::int32_t addr, std::int32_t *data)
{
    std::uint32_t offset{static_cast<std::uint32_t>(addr) - static_cast<std::uint32_t>(CONFIG_MBASE)}; // 脑残编译器，这里auto会解读成ui32，他妈的那每次auto写了后，还得检查一遍，这和没写auto有什么区别，他妈的
    std::uint32_t aligned{offset & ~3u};
    std::uint32_t result{0};
    for (int i = 0; i < 4; i++)
    {
        if (aligned + i < mrom.size())
            result |= static_cast<std::uint32_t>(mrom[aligned + i]) << (8 * i);
    }
    *data = static_cast<std::int32_t>(result);
}
extern "C" void axi_debug_probe(std::int32_t tag, std::int32_t addr, std::int32_t resp)
{
    static const char *names[] = {"ICACHE", "LSU_R", "LSU_B", "SOC_R", "SOC_B", "GPR_W", "WBU_W", "IFU_F", "IC_RESP"};
    auto idx{static_cast<unsigned>(tag)};
    auto name{(idx < 9) ? names[idx] : "UNKNOWN"};
    std::println(std::cerr, "[AXI_DEBUG] {} addr=0x{:08x} data=0x{:08x}", name, static_cast<std::uint32_t>(addr), static_cast<std::uint32_t>(resp));
}
