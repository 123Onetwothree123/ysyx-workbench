export module npc.NPCSimResult;
import std;
export class NPCSimResult final
{
public:
    NPCSimResult() = delete;
    static void Save(
        std::size_t total_cycles,
        std::size_t total_instructions,
        std::size_t instruction_fetch,
        std::size_t execution_complete,
        std::size_t load_data,
        std::size_t store_data,
        std::size_t arithmetic_operation,
        std::size_t memory_access_operation,
        std::size_t control_status_register_operation,
        std::size_t branch_operation,
        std::size_t memory_access_operation_active_cycles,
        std::size_t instruction_fetch_stall_pipeline,
        std::size_t instruction_fetch_stall_axi,
        std::size_t instruction_fetch_stall_ar,
        std::size_t instruction_fetch_stall_r,
        std::size_t instruction_fetch_stall_redirect,
        std::size_t instruction_fetch_stall_idle,
        std::size_t exu_stall_lsu,
        std::size_t load_store_unit_active,
        std::size_t load_store_unit_load_active,
        std::size_t load_store_unit_store_active,
        std::size_t lsu_stall_read_ar,
        std::size_t lsu_stall_read_r,
        std::size_t lsu_stall_write_req,
        std::size_t lsu_stall_write_b);
};
