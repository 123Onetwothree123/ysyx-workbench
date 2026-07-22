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
    static bool first{true};
    if (first) {
        std::println(stderr, "[DEBUG] mrom_read first call: addr=0x{:08x}, mrom.size={}", static_cast<std::uint32_t>(addr), mrom.size());
        first = false;
    }
    std::uint32_t offset{static_cast<std::uint32_t>(addr) - static_cast<std::uint32_t>(CONFIG_MBASE)};
    std::uint32_t aligned{offset & ~3u};
    std::uint32_t result{0};
    for (int i = 0; i < 4; i++)
    {
        if (aligned + i < mrom.size())
            result |= static_cast<std::uint32_t>(mrom[aligned + i]) << (8 * i);
    }
    *data = static_cast<std::int32_t>(result);
}
