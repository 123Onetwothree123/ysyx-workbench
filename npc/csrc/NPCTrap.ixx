module;
export module npc.NPCTrap;
import npc.config;
import std;
import npc.PerfStats;

export class NPCTrap final
{
public:
    NPCTrap() = delete;
    // halt PC 使用完整 XWord；RV32 仍为 32 位，RV64 保留 64 位地址。
    static void Halt(npc::XWord PC, std::uint32_t Code) noexcept;
    // An externally forced stop is a failure, not an architectural GOOD trap.
    // Interactive SDB quit is handled separately by SDB::MainLoop.
    static void Stop(npc::XWord PC = 0) noexcept;
    [[nodiscard]] static bool HasHalted() noexcept;
    [[nodiscard]] static npc::XWord GetPC() noexcept;
    [[nodiscard]] static std::uint32_t GetCode() noexcept;
    [[nodiscard]] static int PrintResult(std::size_t Cycles, std::size_t Instructions);
    static void PrintPerformanceStatistics(
        const PerfStats &stats,
        std::size_t total_cycles,
        std::size_t retired_instructions);
};
