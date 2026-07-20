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
        std::size_t branch_operation,
        std::size_t total_cycles,
        std::size_t instruction_fetch_stall_pipeline,
        std::size_t instruction_fetch_stall_axi,
        std::size_t instruction_fetch_stall_redirect,
        std::size_t instruction_fetch_stall_ar,
        std::size_t instruction_fetch_stall_r,
        std::size_t instruction_fetch_stall_idle,
        std::size_t arithmetic_operation_active_cycles,
        std::size_t memory_access_operation_active_cycles,
        std::size_t control_status_register_operation_active_cycles,
        std::size_t branch_operation_active_cycles,
        std::size_t exu_stall_lsu_cycles,
        std::size_t load_store_unit_active_cycles,
        std::size_t load_store_unit_load_active_cycles,
        std::size_t load_store_unit_store_active_cycles,
        std::size_t lsu_stall_read_ar_cycles,
        std::size_t lsu_stall_read_r_cycles,
        std::size_t lsu_stall_write_req_cycles,
        std::size_t lsu_stall_write_b_cycles,
        std::size_t icache_hit,
        std::size_t icache_miss);
#endif
};
