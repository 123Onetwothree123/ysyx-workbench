module npc.NPCTrap;
import npc.trace.itrace;
namespace
{
    bool Halted{false};
    std::uint32_t HaltPC{0};   // 记录停止的时候的PC
    std::uint32_t HaltCode{0}; // 返回码，0是good，1是bad
} // namespace
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
        std::println("NPC在未触发陷阱的情况下退出");
        return 0;
    }
#ifdef CONFIG_PERF_STATS
    auto ipc{Cycles > 0 ? static_cast<double>(Instructions) / static_cast<double>(Cycles) : 0.0};
#endif
    if (HaltCode == 0)
    {
#ifdef CONFIG_PERF_STATS
        std::println("HIT GOOD TRAP at pc = 0x{0:08x}, cycles = {1}, instructions = {2}, ipc = {3:.4f}", HaltPC, Cycles, Instructions, ipc);
#else
        std::println("HIT GOOD TRAP at pc = 0x{0:08x}, cycles = {1}", HaltPC, Cycles);
#endif
        return 0;
    }
#ifdef CONFIG_PERF_STATS
    std::println(std::cerr, "HIT BAD TRAP at pc = 0x{0:08x}, code = {1}, cycles = {2}, instructions = {3}, ipc = {4:.4f}", HaltPC, HaltCode, Cycles, Instructions, ipc);
#else
    std::println(std::cerr, "HIT BAD TRAP at pc = 0x{0:08x}, code = {1}, cycles = {2}", HaltPC, HaltCode, Cycles);
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
    std::size_t arithmetic_operation_active_cycles,
    std::size_t memory_access_operation_active_cycles,
    std::size_t control_status_register_operation_active_cycles,
    std::size_t branch_operation_active_cycles,
    std::size_t load_store_unit_active_cycles)
{
    std::println("性能计数器");
    std::println("IFU取到指令: {}", instruction_fetch);
    std::println("EXU完成计算: {}", execution_complete);
    std::println("LSU取到数据: {}", load_data);
    std::println("LSU写出数据: {}", store_data);
    std::println("ALU指令: {}", arithmetic_operation);
    std::println("访存指令: {}", memory_access_operation);
    std::println("CSR指令: {}", control_status_register_operation);
    std::println("分支/跳转指令: {}", branch_operation);
    auto instruction_type_sum{arithmetic_operation + memory_access_operation + control_status_register_operation + branch_operation};
    std::println("指令类别合计: {} (应与IFU取指一致)", instruction_type_sum);
    std::println("IFU取指: {}", instruction_fetch);
    std::println("EXU完成: {} (应与IFU取指接近)", execution_complete);
    auto load_store_sum{load_data + store_data};
    std::println("LSU合计: {} (应与访存指令一致)", load_store_sum);

    std::println("");
    std::println("指令类别占比与平均周期:");
    if (instruction_fetch > 0)
    {
        auto total_instructions = static_cast<double>(instruction_fetch);
        std::println("ALU指令占比: {:.1f}%", 100.0 * arithmetic_operation / total_instructions);
        std::println("访存指令占比: {:.1f}%", 100.0 * memory_access_operation / total_instructions);
        if (memory_access_operation > 0)
        {
            std::println("访存指令平均周期: {:.2f}", static_cast<double>(memory_access_operation_active_cycles) / memory_access_operation);
        }
        std::println("CSR指令占比: {:.1f}%", 100.0 * control_status_register_operation / total_instructions);
        std::println("分支/跳转指令占比: {:.1f}%", 100.0 * branch_operation / total_instructions);
    }

    std::println("");
    std::println("IFU取不到指令原因分析:");
    if (total_cycles > 0)
    {
        auto total = static_cast<double>(total_cycles);
        std::println("流水线阻塞(IFU有数据但下游不接): {} 周期, 占比 {:.1f}%", instruction_fetch_stall_pipeline, 100.0 * instruction_fetch_stall_pipeline / total);
        std::println("AXI总线等待(AR/R通道未就绪): {} 周期, 占比 {:.1f}%", instruction_fetch_stall_axi, 100.0 * instruction_fetch_stall_axi / total);
        std::println("跳转冲刷(取指结果被丢弃): {} 周期, 占比 {:.1f}%", instruction_fetch_stall_redirect, 100.0 * instruction_fetch_stall_redirect / total);
    }

    std::println("");
    std::println("LSU平均访存延迟:");
    auto load_store_total{load_data + store_data};
    if (load_store_total > 0)
    {
        std::println("平均延迟: {:.2f} 周期", static_cast<double>(load_store_unit_active_cycles) / load_store_total);
    }
}
#endif
