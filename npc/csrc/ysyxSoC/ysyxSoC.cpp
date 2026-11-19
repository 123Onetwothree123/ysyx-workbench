module;
module npc.ysyxSoC;
import npc.config;
import npc.SimulationContext;
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

extern "C" void flash_read(std::int32_t addr, std::int32_t *data)
{
    std::uint32_t offset{static_cast<std::uint32_t>(addr)}; // 已经是偏移，不用减基址
    std::uint32_t aligned{offset & ~3u};
    *data = static_cast<std::int32_t>(
        LoadU32(SimulationContext::Active().Flash(), aligned));
}
extern "C" void mrom_read(std::int32_t addr, std::int32_t *data)
{
    std::uint32_t offset{static_cast<std::uint32_t>(addr) - static_cast<std::uint32_t>(npc::SocMBase)};
    std::uint32_t aligned{offset & ~3u};
    *data = static_cast<std::int32_t>(
        LoadU32(SimulationContext::Active().Mrom(), aligned));
}
