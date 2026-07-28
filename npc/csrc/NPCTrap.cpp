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
    std::size_t lsu_stall_write_b_cycles,
    std::size_t icache_hit,
    std::size_t icache_miss,
    std::size_t idu_stall_raw,
    std::size_t idu_stall_raw_loaduse,
    std::size_t idu_stall_raw_alu,
    std::size_t exu_idle_noinput,
    std::size_t trap_count)
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
        auto total {static_cast<double>(total_cycles)};
        std::println("流水线阻塞(IFU有数据但下游不接): {} 周期, 占比 {:.1f}%", instruction_fetch_stall_pipeline, 100.0 * instruction_fetch_stall_pipeline / total);
        std::println("AXI总线等待(AR/R通道未就绪): {} 周期, 占比 {:.1f}%", instruction_fetch_stall_axi, 100.0 * instruction_fetch_stall_axi / total);
        std::println("  其中 AR通道等待(请求未接受): {} 周期, 占比 {:.1f}%", instruction_fetch_stall_ar, 100.0 * instruction_fetch_stall_ar / total);
        std::println("  其中 R通道等待(数据未返回): {} 周期, 占比 {:.1f}%", instruction_fetch_stall_r, 100.0 * instruction_fetch_stall_r / total);
        std::println("跳转冲刷(取指结果被丢弃): {} 周期, 占比 {:.1f}%", instruction_fetch_stall_redirect, 100.0 * instruction_fetch_stall_redirect / total);
        std::println("IFU空闲(无取指请求): {} 周期, 占比 {:.1f}%", instruction_fetch_stall_idle, 100.0 * instruction_fetch_stall_idle / total);
    }
    std::println("");
    std::println("EXU流水线阻塞分析:");
    if (total_cycles > 0)
    {
        auto total{static_cast<double>(total_cycles)};
        std::println("EXU等待LSU完成(流水线stall): {} 周期, 占比 {:.1f}%", exu_stall_lsu_cycles, 100.0 * exu_stall_lsu_cycles / total);
        std::println("EXU空转无输入(上游供给不足): {} 周期, 占比 {:.1f}%", exu_idle_noinput, 100.0 * exu_idle_noinput / total);
    }
    std::println("");
    std::println("IDU数据冒险阻塞分析:");
    if (total_cycles > 0)
    {
        auto total{static_cast<double>(total_cycles)};
        std::println("RAW阻塞合计: {} 周期, 占比 {:.1f}%", idu_stall_raw, 100.0 * idu_stall_raw / total);
        std::println("  load-use(等LSU数据,转发无法消除): {} 周期, 占比 {:.1f}%", idu_stall_raw_loaduse, 100.0 * idu_stall_raw_loaduse / total);
        std::println("  ALU依赖(转发可消除,即转发理想收益): {} 周期, 占比 {:.1f}%", idu_stall_raw_alu, 100.0 * idu_stall_raw_alu / total);
    }
    std::println("");
    std::println("异常/中断提交: {} 次", trap_count);
    std::println("");
    std::println("LSU平均访存延迟:");
    auto load_store_total{load_data + store_data};
    if (load_store_total > 0)
    {
        std::println("平均延迟: {:.2f} 周期", static_cast<double>(load_store_unit_active_cycles) / load_store_total);
    }
    if (load_data > 0)
    {
        std::println("  读延迟: {:.2f} 周期 ({} 次)", static_cast<double>(load_store_unit_load_active_cycles) / load_data, load_data);
    }
    if (store_data > 0)
    {
        std::println("  写延迟: {:.2f} 周期 ({} 次)", static_cast<double>(load_store_unit_store_active_cycles) / store_data, store_data);
    }
    std::println("");
    std::println("LSU访存延迟分解:");
    auto lsu_active_total{load_store_unit_active_cycles};
    if (lsu_active_total > 0)
    {
        auto active_total_d = static_cast<double>(lsu_active_total);
        std::println("  AR等待(读请求握手): {} 周期, 占比 {:.1f}%", lsu_stall_read_ar_cycles, 100.0 * lsu_stall_read_ar_cycles / active_total_d);
        std::println("  R等待(读数据返回):  {} 周期, 占比 {:.1f}%", lsu_stall_read_r_cycles, 100.0 * lsu_stall_read_r_cycles / active_total_d);
        std::println("  AW/W等待(写请求握手): {} 周期, 占比 {:.1f}%", lsu_stall_write_req_cycles, 100.0 * lsu_stall_write_req_cycles / active_total_d);
        std::println("  B等待(写响应返回):  {} 周期, 占比 {:.1f}%", lsu_stall_write_b_cycles, 100.0 * lsu_stall_write_b_cycles / active_total_d);
    }
    std::println("");
    std::println("ICache 性能:");
    std::println("  命中: {} 次", icache_hit);
    std::println("  缺失: {} 次", icache_miss);
    if (icache_hit + icache_miss > 0)
    {
        auto hit_rate{static_cast<double>(icache_hit) / (icache_hit + icache_miss)};
        std::println("  命中率: {:.1f}%", 100.0 * hit_rate);
        if (icache_miss > 0)
        {
            auto miss_cycles{static_cast<double>(instruction_fetch_stall_ar + instruction_fetch_stall_r)};
            auto miss_avg{miss_cycles / icache_miss};
            auto amat{1.0 + (1.0 - hit_rate) * miss_avg};
            std::println("  缺失平均延迟: {:.1f} 周期", miss_avg);
            std::println("  AMAT: {:.1f} 周期", amat);
        }
    }
}
#endif
