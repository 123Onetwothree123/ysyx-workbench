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
void NPCTrap::PrintPerformanceStatistics(const PerfStats &stats, std::size_t total_cycles)
{
    std::println("性能计数器");
    std::println("IFU取到指令: {}", stats.instruction_fetch);
    std::println("EXU完成计算: {}", stats.execution_complete);
    std::println("LSU取到数据: {}", stats.load_data);
    std::println("LSU写出数据: {}", stats.store_data);
    std::println("ALU指令: {}", stats.arithmetic_operation);
    std::println("访存指令: {}", stats.memory_access_operation);
    std::println("CSR指令: {}", stats.control_status_register_operation);
    std::println("分支/跳转指令: {}", stats.branch_operation);
    std::println("  其中 jal指令: {}", stats.jal_operation);
    std::println("  其中 jalr指令: {}", stats.jalr_operation);
    std::println("  其中 条件分支: {}", stats.branch_operation - stats.jal_operation - stats.jalr_operation);
    auto instruction_type_sum{stats.arithmetic_operation + stats.memory_access_operation + stats.control_status_register_operation + stats.branch_operation};
    std::println("指令类别合计: {} (应与IFU取指一致)", instruction_type_sum);
    std::println("IFU取指: {}", stats.instruction_fetch);
    std::println("EXU完成: {} (应与IFU取指接近)", stats.execution_complete);
    auto load_store_sum{stats.load_data + stats.store_data};
    std::println("LSU合计: {} (应与访存指令一致)", load_store_sum);
    std::println();
    std::println("指令类别占比与平均周期:");
    if (stats.instruction_fetch > 0)
    {
        auto total_instructions = static_cast<double>(stats.instruction_fetch);
        std::println("ALU指令占比: {:.1f}%", 100.0 * stats.arithmetic_operation / total_instructions);
        std::println("访存指令占比: {:.1f}%", 100.0 * stats.memory_access_operation / total_instructions);
        if (stats.memory_access_operation > 0)
        {
            std::println("访存指令平均周期: {:.2f}", static_cast<double>(stats.memory_access_operation_active_cycle) / stats.memory_access_operation);
        }
        std::println("CSR指令占比: {:.1f}%", 100.0 * stats.control_status_register_operation / total_instructions);
        std::println("分支/跳转指令占比: {:.1f}%", 100.0 * stats.branch_operation / total_instructions);
    }
    std::println();
    std::println("IFU取不到指令原因分析:");
    if (total_cycles > 0)
    {
        auto total {static_cast<double>(total_cycles)};
        std::println("流水线阻塞(IFU有数据但下游不接): {} 周期, 占比 {:.1f}%", stats.instruction_fetch_stall_pipeline, 100.0 * stats.instruction_fetch_stall_pipeline / total);
        std::println("AXI总线等待(AR/R通道未就绪): {} 周期, 占比 {:.1f}%", stats.instruction_fetch_stall_axi, 100.0 * stats.instruction_fetch_stall_axi / total);
        std::println("  其中 AR通道等待(请求未接受): {} 周期, 占比 {:.1f}%", stats.instruction_fetch_stall_ar, 100.0 * stats.instruction_fetch_stall_ar / total);
        std::println("  其中 R通道等待(数据未返回): {} 周期, 占比 {:.1f}%", stats.instruction_fetch_stall_r, 100.0 * stats.instruction_fetch_stall_r / total);
        std::println("跳转冲刷(取指结果被丢弃): {} 周期, 占比 {:.1f}%", stats.instruction_fetch_stall_redirect, 100.0 * stats.instruction_fetch_stall_redirect / total);
        std::println("IFU空闲(无取指请求): {} 周期, 占比 {:.1f}%", stats.instruction_fetch_stall_idle, 100.0 * stats.instruction_fetch_stall_idle / total);
    }
    std::println();
    std::println("EXU流水线阻塞分析:");
    if (total_cycles > 0)
    {
        auto total{static_cast<double>(total_cycles)};
        std::println("EXU被下游阻塞(有指令但本拍未完成): {} 周期, 占比 {:.1f}%", stats.exu_stall_lsu, 100.0 * stats.exu_stall_lsu / total);
        std::println("EXU空转无输入(上游供给不足): {} 周期, 占比 {:.1f}%", stats.exu_idle_noinput, 100.0 * stats.exu_idle_noinput / total);
        std::println("EX/MEM等待槽占用(5级拆分买到的访存重叠): {} 周期, 占比 {:.1f}%", stats.mem_waitslot, 100.0 * stats.mem_waitslot / total);
    }
    std::println();
    std::println("IDU数据冒险阻塞分析:");
    if (total_cycles > 0)
    {
        auto total{static_cast<double>(total_cycles)};
        std::println("RAW阻塞合计: {} 周期, 占比 {:.1f}%", stats.idu_stall_raw, 100.0 * stats.idu_stall_raw / total);
        std::println("  load-use(等LSU数据,转发无法消除): {} 周期, 占比 {:.1f}%", stats.idu_stall_raw_loaduse, 100.0 * stats.idu_stall_raw_loaduse / total);
        std::println("  ALU依赖(转发可消除,即转发理想收益): {} 周期, 占比 {:.1f}%", stats.idu_stall_raw_alu, 100.0 * stats.idu_stall_raw_alu / total);
    }
    std::println();
    std::println("异常/中断提交: {} 次", stats.trap_count);
    std::println();
    std::println("LSU平均访存延迟:");
    auto load_store_total{stats.load_data + stats.store_data};
    if (load_store_total > 0)
    {
        std::println("平均延迟: {:.2f} 周期", static_cast<double>(stats.load_store_unit_active_cycle) / load_store_total);
    }
    if (stats.load_data > 0)
    {
        std::println("  读延迟: {:.2f} 周期 ({} 次)", static_cast<double>(stats.load_store_unit_load_active_cycle) / stats.load_data, stats.load_data);
    }
    if (stats.store_data > 0)
    {
        std::println("  写延迟: {:.2f} 周期 ({} 次)", static_cast<double>(stats.load_store_unit_store_active_cycle) / stats.store_data, stats.store_data);
    }
    std::println();
    std::println("LSU访存延迟分解:");
    auto lsu_active_total{stats.load_store_unit_active_cycle};
    if (lsu_active_total > 0)
    {
        auto active_total_d = static_cast<double>(lsu_active_total);
        std::println("  AR等待(读请求握手): {} 周期, 占比 {:.1f}%", stats.lsu_stall_read_ar, 100.0 * stats.lsu_stall_read_ar / active_total_d);
        std::println("  R等待(读数据返回):  {} 周期, 占比 {:.1f}%", stats.lsu_stall_read_r, 100.0 * stats.lsu_stall_read_r / active_total_d);
        std::println("  AW/W等待(写请求握手): {} 周期, 占比 {:.1f}%", stats.lsu_stall_write_req, 100.0 * stats.lsu_stall_write_req / active_total_d);
        std::println("  B等待(写响应返回):  {} 周期, 占比 {:.1f}%", stats.lsu_stall_write_b, 100.0 * stats.lsu_stall_write_b / active_total_d);
    }
    std::println();
    std::println("ICache 性能:");
    std::println("  命中: {} 次", stats.icache_hit);
    std::println("  缺失: {} 次", stats.icache_miss);
    if (stats.icache_hit + stats.icache_miss > 0)
    {
        auto hit_rate{static_cast<double>(stats.icache_hit) / (stats.icache_hit + stats.icache_miss)};
        std::println("  命中率: {:.1f}%", 100.0 * hit_rate);
        if (stats.icache_miss > 0)
        {
            auto miss_cycles{static_cast<double>(stats.instruction_fetch_stall_ar + stats.instruction_fetch_stall_r)};
            auto miss_avg{miss_cycles / stats.icache_miss};
            auto amat{1.0 + (1.0 - hit_rate) * miss_avg};
            std::println("  缺失平均延迟: {:.1f} 周期", miss_avg);
            std::println("  AMAT: {:.1f} 周期", amat);
        }
    }
}
#endif
