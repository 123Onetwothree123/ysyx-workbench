module;
module npc.ysyxSoC;
// 兜底值必须与 Kconfig 的 CONFIG_MBASE 默认值一致（与 SoCMemoryMap 模块保持同步）
#ifndef CONFIG_MBASE
#define CONFIG_MBASE 0x30000000u
#endif

static_assert(std::endian::native == std::endian::little, "NPC 仅支持小端主机");

static std::uint32_t LoadU32(const std::vector<std::uint8_t> &Memory, std::uint32_t Aligned)
{
    std::uint32_t result{0};
    if (Aligned < Memory.size())
    {
        const auto n{std::min<std::size_t>(4, Memory.size() - Aligned)};
        std::memcpy(&result, Memory.data() + Aligned, n);
    }
    return result;
}

std::vector<std::uint8_t> mrom;
std::vector<std::uint8_t> FlashMemory;
extern "C" void flash_read(std::int32_t addr, std::int32_t *data)
{
    std::uint32_t offset{static_cast<std::uint32_t>(addr)}; // 已经是偏移，不用减基址
    std::uint32_t aligned{offset & ~3u};
    *data = static_cast<std::int32_t>(LoadU32(FlashMemory, aligned));
}
extern "C" void mrom_read(std::int32_t addr, std::int32_t *data)
{
    std::uint32_t offset{static_cast<std::uint32_t>(addr) - static_cast<std::uint32_t>(CONFIG_MBASE)}; // 脑残编译器，这里auto会解读成ui32，他妈的那每次auto写了后，还得检查一遍，这和没写auto有什么区别，他妈的
    std::uint32_t aligned{offset & ~3u};
    *data = static_cast<std::int32_t>(LoadU32(mrom, aligned));
}
