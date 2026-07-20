module;
#ifdef VRISCV32E_NPC
#include "Vriscv32e_npc_SimTop.h"
#define TOP_MODULE Vriscv32e_npc_SimTop
#else
#include "VysyxSoCFull.h"
#define TOP_MODULE VysyxSoCFull
#endif
export module npc.DUT;
import std;

export class DUT
{
private:
    std::unique_ptr<TOP_MODULE> dut;
    std::size_t cycle{0};
    std::size_t instructions{0};
    std::size_t instruction_fetch_count{0};
    std::size_t execution_complete_count{0};
    std::size_t load_data_count{0};
    std::size_t store_data_count{0};
    std::size_t arithmetic_operation_count{0};
    std::size_t memory_access_operation_count{0};
    std::size_t control_status_register_operation_count{0};
    std::size_t branch_operation_count{0};
    std::size_t instruction_fetch_stall_pipeline_count{0};
    std::size_t instruction_fetch_stall_axi_count{0};
    std::size_t instruction_fetch_stall_ar_count{0};
    std::size_t instruction_fetch_stall_r_count{0};
    std::size_t instruction_fetch_stall_redirect_count{0};
    std::size_t instruction_fetch_stall_idle_count{0};
    std::size_t execution_active_cycle_count{0};
    std::size_t exu_stall_lsu_count{0};
    std::size_t arithmetic_operation_active_cycle_count{0};
    std::size_t memory_access_operation_active_cycle_count{0};
    std::size_t control_status_register_operation_active_cycle_count{0};
    std::size_t branch_operation_active_cycle_count{0};
    std::size_t load_store_unit_active_cycle_count{0};
    std::size_t load_store_unit_load_active_cycle_count{0};
    std::size_t load_store_unit_store_active_cycle_count{0};
    std::size_t lsu_stall_read_ar_count{0};
    std::size_t lsu_stall_read_r_count{0};
    std::size_t lsu_stall_write_req_count{0};
    std::size_t lsu_stall_write_b_count{0};
    std::size_t icache_hit_count{0};
    std::size_t icache_miss_count{0};

public:
    DUT();
    ~DUT() = default;
    // 运算符重载，少写点代码
    TOP_MODULE &operator*();
    TOP_MODULE *operator->();
    void eval();
    void final();
    void step();
    void reset();
    std::size_t GetCycle() const;
    std::size_t GetInstructions() const;
    std::size_t GetInstructionFetchCount() const;
    std::size_t GetExecutionCompleteCount() const;
    std::size_t GetLoadDataCount() const;
    std::size_t GetStoreDataCount() const;
    std::size_t GetArithmeticOperationCount() const;
    std::size_t GetMemoryAccessOperationCount() const;
    std::size_t GetControlStatusRegisterOperationCount() const;
    std::size_t GetBranchOperationCount() const;
    std::size_t GetInstructionFetchStallPipelineCount() const;
    std::size_t GetInstructionFetchStallAXICount() const;
    std::size_t GetInstructionFetchStallARCount() const;
    std::size_t GetInstructionFetchStallRCount() const;
    std::size_t GetInstructionFetchStallRedirectCount() const;
    std::size_t GetInstructionFetchStallIdleCount() const;
    std::size_t GetExecutionActiveCycleCount() const;
    std::size_t GetEXUStallLSUCount() const;
    std::size_t GetArithmeticOperationActiveCycleCount() const;
    std::size_t GetMemoryAccessOperationActiveCycleCount() const;
    std::size_t GetControlStatusRegisterOperationActiveCycleCount() const;
    std::size_t GetBranchOperationActiveCycleCount() const;
    std::size_t GetLoadStoreUnitActiveCycleCount() const;
    std::size_t GetLoadStoreUnitLoadActiveCycleCount() const;
    std::size_t GetLoadStoreUnitStoreActiveCycleCount() const;
    std::size_t GetLSUStallReadARCount() const;
    std::size_t GetLSUStallReadRCount() const;
    std::size_t GetLSUStallWriteReqCount() const;
    std::size_t GetLSUStallWriteBCount() const;
    std::size_t GetICacheHitCount() const;
    std::size_t GetICacheMissCount() const;
    // 给sdb的
    [[nodiscard]] std::expected<std::uint32_t, std::string> ReadGPR(std::uint32_t index);
    [[nodiscard]] std::expected<std::uint32_t, std::string> ReadPC();
    [[nodiscard]] std::expected<std::uint32_t, std::string> ReadMemory(std::uint32_t addr, std::size_t size);
};
