module npc.NPCTrap;
import npc.trace.itrace;
import npc.log;
namespace
{
    bool Halted{false};
    std::uint32_t HaltPC{0};   // 记录停止的时候的PC
    std::uint32_t HaltCode{0}; // 返回码，0是good，1是bad
}
void NPCTrap::Halt(std::uint32_t PC, std::uint32_t Code) noexcept
{
    Halted = true;
    HaltPC = PC;
    HaltCode = Code;
}
void NPCTrap::Stop() noexcept
{
    Halted = true;
}
bool NPCTrap::HasHalted() noexcept
{
    return Halted;
}
std::uint32_t NPCTrap::GetPC() noexcept
{
    return HaltPC;
}
std::uint32_t NPCTrap::GetCode() noexcept
{
    return HaltCode;
}
int NPCTrap::PrintResult(std::size_t Cycles, std::size_t Instructions)
{
    if (!Halted)
    {
        log_info("NPC在未触发陷阱的情况下退出");
        return 0;
    }
#ifdef CONFIG_PERF_STATS
    auto ipc{Cycles > 0 ? static_cast<double>(Instructions) / static_cast<double>(Cycles) : 0.0};
#endif
    if (HaltCode == 0)
    {
#ifdef CONFIG_PERF_STATS
        log_info("HIT GOOD TRAP at pc = 0x{0:08x}, cycles = {1}, instructions = {2}, ipc = {3:.4f}", HaltPC, Cycles, Instructions, ipc);
#else
        log_info("HIT GOOD TRAP at pc = 0x{0:08x}, cycles = {1}", HaltPC, Cycles);
#endif
        return 0;
    }
#ifdef CONFIG_PERF_STATS
    log_error("HIT BAD TRAP at pc = 0x{0:08x}, code = {1}, cycles = {2}, instructions = {3}, ipc = {4:.4f}", HaltPC, HaltCode, Cycles, Instructions, ipc);
#else
    log_error("HIT BAD TRAP at pc = 0x{0:08x}, code = {1}, cycles = {2}", HaltPC, HaltCode, Cycles);
#endif
    PrintIringbuf(HaltPC);
    return 1;
}
#ifdef CONFIG_PERF_STATS
void NPCTrap::PrintPerformanceStatistics(
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
    std::size_t lsu_stall_write_b_cycles)
{
    std::ostringstream oss;
    oss << "性能计数器\n";
    oss << std::format("IFU取到指令: {}\n", instruction_fetch);
    oss << std::format("EXU完成计算: {}\n", execution_complete);
    oss << std::format("LSU取到数据: {}\n", load_data);
    oss << std::format("LSU写出数据: {}\n", store_data);
    oss << std::format("ALU指令: {}\n", arithmetic_operation);
    oss << std::format("访存指令: {}\n", memory_access_operation);
    oss << std::format("CSR指令: {}\n", control_status_register_operation);
    oss << std::format("分支/跳转指令: {}\n", branch_operation);
    auto instruction_type_sum{arithmetic_operation + memory_access_operation + control_status_register_operation + branch_operation};
    oss << std::format("指令类别合计: {} (应与IFU取指一致)\n", instruction_type_sum);
    oss << std::format("IFU取指: {}\n", instruction_fetch);
    oss << std::format("EXU完成: {} (应与IFU取指接近)\n", execution_complete);
    auto load_store_sum{load_data + store_data};
    oss << std::format("LSU合计: {} (应与访存指令一致)\n", load_store_sum);
    oss << "\n";
    oss << "指令类别占比与平均周期:\n";
    if (instruction_fetch > 0)
    {
        auto total_instructions = static_cast<double>(instruction_fetch);
        oss << std::format("ALU指令占比: {:.1f}%\n", 100.0 * arithmetic_operation / total_instructions);
        oss << std::format("访存指令占比: {:.1f}%\n", 100.0 * memory_access_operation / total_instructions);
        if (memory_access_operation > 0)
        {
            oss << std::format("访存指令平均周期: {:.2f}\n", static_cast<double>(memory_access_operation_active_cycles) / memory_access_operation);
        }
        oss << std::format("CSR指令占比: {:.1f}%\n", 100.0 * control_status_register_operation / total_instructions);
        oss << std::format("分支/跳转指令占比: {:.1f}%\n", 100.0 * branch_operation / total_instructions);
    }
    oss << "\n";
    oss << "IFU取不到指令原因分析:\n";
    if (total_cycles > 0)
    {
        auto total {static_cast<double>(total_cycles)};
        oss << std::format("流水线阻塞(IFU有数据但下游不接): {} 周期, 占比 {:.1f}%\n", instruction_fetch_stall_pipeline, 100.0 * instruction_fetch_stall_pipeline / total);
        oss << std::format("AXI总线等待(AR/R通道未就绪): {} 周期, 占比 {:.1f}%\n", instruction_fetch_stall_axi, 100.0 * instruction_fetch_stall_axi / total);
        oss << std::format("  其中 AR通道等待(请求未接受): {} 周期, 占比 {:.1f}%\n", instruction_fetch_stall_ar, 100.0 * instruction_fetch_stall_ar / total);
        oss << std::format("  其中 R通道等待(数据未返回): {} 周期, 占比 {:.1f}%\n", instruction_fetch_stall_r, 100.0 * instruction_fetch_stall_r / total);
        oss << std::format("跳转冲刷(取指结果被丢弃): {} 周期, 占比 {:.1f}%\n", instruction_fetch_stall_redirect, 100.0 * instruction_fetch_stall_redirect / total);
        oss << std::format("IFU空闲(无取指请求): {} 周期, 占比 {:.1f}%\n", instruction_fetch_stall_idle, 100.0 * instruction_fetch_stall_idle / total);
    }
    oss << "\n";
    oss << "EXU流水线阻塞分析:\n";
    if (total_cycles > 0)
    {
        auto total{static_cast<double>(total_cycles)};
        oss << std::format("EXU等待LSU完成(流水线stall): {} 周期, 占比 {:.1f}%\n", exu_stall_lsu_cycles, 100.0 * exu_stall_lsu_cycles / total);
    }
    oss << "\n";
    oss << "LSU平均访存延迟:\n";
    auto load_store_total{load_data + store_data};
    if (load_store_total > 0)
    {
        oss << std::format("平均延迟: {:.2f} 周期\n", static_cast<double>(load_store_unit_active_cycles) / load_store_total);
    }
    if (load_data > 0)
    {
        oss << std::format("  读延迟: {:.2f} 周期 ({} 次)\n", static_cast<double>(load_store_unit_load_active_cycles) / load_data, load_data);
    }
    if (store_data > 0)
    {
        oss << std::format("  写延迟: {:.2f} 周期 ({} 次)\n", static_cast<double>(load_store_unit_store_active_cycles) / store_data, store_data);
    }
    oss << "\n";
    oss << "LSU访存延迟分解:\n";
    auto lsu_active_total{load_store_unit_active_cycles};
    if (lsu_active_total > 0)
    {
        auto active_total_d = static_cast<double>(lsu_active_total);
        oss << std::format("  AR等待(读请求握手): {} 周期, 占比 {:.1f}%\n", lsu_stall_read_ar_cycles, 100.0 * lsu_stall_read_ar_cycles / active_total_d);
        oss << std::format("  R等待(读数据返回):  {} 周期, 占比 {:.1f}%\n", lsu_stall_read_r_cycles, 100.0 * lsu_stall_read_r_cycles / active_total_d);
        oss << std::format("  AW/W等待(写请求握手): {} 周期, 占比 {:.1f}%\n", lsu_stall_write_req_cycles, 100.0 * lsu_stall_write_req_cycles / active_total_d);
        oss << std::format("  B等待(写响应返回):  {} 周期, 占比 {:.1f}%\n", lsu_stall_write_b_cycles, 100.0 * lsu_stall_write_b_cycles / active_total_d);
    }
    auto report{oss.str()};
    std::println("{}", report);
    log_info("{}", report);
}
#endif
