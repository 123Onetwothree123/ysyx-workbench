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
    static int call_count{0};
    if (call_count == 0) {
        char buf[256];
        int len = snprintf(buf, sizeof(buf), "[DEBUG] mrom_read first call: addr=0x%x, mrom.size=%zu\n", (unsigned)addr, mrom.size());
        write(2, buf, len);
    }
    ++call_count;
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
