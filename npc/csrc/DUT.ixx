module;
#include "VysyxSoCFull.h"
export module npc.DUT;
import std;

export class DUT
{
private:
    std::unique_ptr<VysyxSoCFull> dut;
    std::size_t cycle{0};
    std::size_t insts{0};
    std::size_t perf_ifu_fetch_cnt{0};
    std::size_t perf_exu_done_cnt{0};
    std::size_t perf_lsu_load_cnt{0};
    std::size_t perf_lsu_store_cnt{0};
    std::size_t perf_alu_op_cnt{0};
    std::size_t perf_mem_op_cnt{0};
    std::size_t perf_csr_op_cnt{0};
    std::size_t perf_branch_op_cnt{0};

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
    std::size_t GetInsts() const;
    std::size_t GetPerfIfuFetch() const  { return perf_ifu_fetch_cnt; }
    std::size_t GetPerfExuDone() const   { return perf_exu_done_cnt; }
    std::size_t GetPerfLsuLoad() const   { return perf_lsu_load_cnt; }
    std::size_t GetPerfLsuStore() const  { return perf_lsu_store_cnt; }
    std::size_t GetPerfAluOp() const     { return perf_alu_op_cnt; }
    std::size_t GetPerfMemOp() const     { return perf_mem_op_cnt; }
    std::size_t GetPerfCsrOp() const     { return perf_csr_op_cnt; }
    std::size_t GetPerfBranchOp() const  { return perf_branch_op_cnt; }
    // 给sdb的
    [[nodiscard]] std::expected<std::uint32_t, std::string> ReadGPR(std::uint32_t index);
    [[nodiscard]] std::expected<std::uint32_t, std::string> ReadPC();
    [[nodiscard]] std::expected<std::uint32_t, std::string> ReadMemory(std::uint32_t addr, std::size_t size);
};
