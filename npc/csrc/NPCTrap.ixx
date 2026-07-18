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
    [[nodiscard]] static int PrintResult(std::size_t Cycles, std::size_t Insts);
#ifdef CONFIG_PERF_STATS
    static void PrintPerfStats(
        std::size_t perf_ifu_fetch,
        std::size_t perf_exu_done,
        std::size_t perf_lsu_load,
        std::size_t perf_lsu_store,
        std::size_t perf_alu_op,
        std::size_t perf_mem_op,
        std::size_t perf_csr_op,
        std::size_t perf_branch_op);
#endif
};
