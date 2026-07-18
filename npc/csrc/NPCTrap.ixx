export module npc.NPCTrap;
import std;

export class NPCTrap final
{
public:
    NPCTrap() = delete;
    static void Halt(std::uint32_t PC, std::uint32_t Code) noexcept;
    static void Stop() noexcept;
    [[nodiscard]] static bool HasHalted() noexcept;
    [[nodiscard]] static std::uint32_t GetPC() noexcept;
    [[nodiscard]] static std::uint32_t GetCode() noexcept;
    [[nodiscard]] static int PrintResult(std::size_t Cycles, std::size_t Instructions);
#ifdef CONFIG_PERF_STATS
    static void PrintPerformanceStatistics(
        std::size_t instruction_fetch,
        std::size_t execution_complete,
        std::size_t load_data,
        std::size_t store_data,
        std::size_t arithmetic_operation,
        std::size_t memory_access_operation,
        std::size_t control_status_register_operation,
        std::size_t branch_operation);
#endif
};
