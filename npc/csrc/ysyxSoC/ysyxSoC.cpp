module;
module npc.ysyxSoC;
import ysyx_26030103_Platform;
import npc.config;
import npc.SimulationContext;
static_assert(std::endian::native == std::endian::little, "NPC 仅支持小端主机");

extern "C" void flash_read(std::int32_t addr, std::int32_t *data)
{
    std::uint32_t offset{static_cast<std::uint32_t>(addr)}; // 已经是偏移，不用减基址
    std::uint32_t aligned{offset & ~3u};
    *data = static_cast<std::int32_t>(ysyx_26030103::LoadLittleEndian32(
        SimulationContext::Active().Flash(),
        aligned,
        ysyx_26030103::LoadPolicy::ZeroFill));
}
extern "C" void mrom_read(std::int32_t addr, std::int32_t *data)
{
    std::uint32_t offset{static_cast<std::uint32_t>(addr) - static_cast<std::uint32_t>(npc::SocMBase)};
    std::uint32_t aligned{offset & ~3u};
    *data = static_cast<std::int32_t>(ysyx_26030103::LoadLittleEndian32(
        SimulationContext::Active().Mrom(),
        aligned,
        ysyx_26030103::LoadPolicy::ZeroFill));
}
