module;
#include "VysyxSoCFull.h"
export module npc.DUT;
import std;

export class DUT
{
private:
    std::unique_ptr<VysyxSoCFull> dut;
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

public:
    DUT();
    ~DUT() = default;
    // 运算符重载，少写点代码
    VysyxSoCFull &operator*();
    VysyxSoCFull *operator->();
    void eval();
    void final();
    void step();
    void reset();
    std::size_t GetCycle() const;
    std::size_t GetInstructions() const;
    std::size_t GetInstructionFetchCount() const           { return instruction_fetch_count; }
    std::size_t GetExecutionCompleteCount() const          { return execution_complete_count; }
    std::size_t GetLoadDataCount() const                   { return load_data_count; }
    std::size_t GetStoreDataCount() const                  { return store_data_count; }
    std::size_t GetArithmeticOperationCount() const        { return arithmetic_operation_count; }
    std::size_t GetMemoryAccessOperationCount() const      { return memory_access_operation_count; }
    std::size_t GetControlStatusRegisterOperationCount() const { return control_status_register_operation_count; }
    std::size_t GetBranchOperationCount() const            { return branch_operation_count; }
    // 给sdb的
    [[nodiscard]] std::expected<std::uint32_t, std::string> ReadGPR(std::uint32_t index);
    [[nodiscard]] std::expected<std::uint32_t, std::string> ReadPC();
    [[nodiscard]] std::expected<std::uint32_t, std::string> ReadMemory(std::uint32_t addr, std::size_t size);
};
