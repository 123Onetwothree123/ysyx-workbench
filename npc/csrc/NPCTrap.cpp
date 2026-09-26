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
    // Once a checker has reported failure, a later architectural/custom halt
    // must not downgrade the run to GOOD.  A late failure is still allowed to
    // replace an earlier GOOD halt (for example the final DiffTest check).
    if (Halted && HaltCode != 0 && Code == 0)
        return;
    Halted = true;
    HaltPC = PC;
    HaltCode = Code;
}
void NPCTrap::Stop(std::uint32_t PC) noexcept
{
    Halt(PC, 1);
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
        std::println(std::cerr, "NPC在未触发陷阱的情况下退出");
        return 1;
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
#ifdef CONFIG_IRINGBUF
    PrintIringbuf(HaltPC);
#endif
    return 1;
}
#ifdef CONFIG_PERF_STATS
void NPCTrap::PrintPerformanceStatistics(
    const PerfStats &stats,
    std::size_t total_cycles,
    std::size_t retired_instructions)
{
    std::println("性能计数器");
    std::println("退休指令: {}", retired_instructions);
    std::println("IFU交付指令响应(含后续可能冲刷的指令): {}", stats.instruction_fetch);
    std::println("EXU输出握手: {}", stats.execution_complete);
    std::println("LSU完成读操作(含故障完成): {}", stats.load_data);
    std::println("LSU完成写操作(含故障完成): {}", stats.store_data);
    std::println("ALU指令: {}", stats.arithmetic_operation);
#ifdef CONFIG_RV32_M
    std::println("MDU请求: {}", stats.mdu_request);
    std::println("MDU完成: {}", stats.mdu_complete);
    std::println("  MUL: {}", stats.mdu_mul);
    std::println("  MULH: {}", stats.mdu_mulh);
    std::println("  MULHSU: {}", stats.mdu_mulhsu);
    std::println("  MULHU: {}", stats.mdu_mulhu);
    std::println("  DIV: {}", stats.mdu_div);
    std::println("  DIVU: {}", stats.mdu_divu);
    std::println("  REM: {}", stats.mdu_rem);
    std::println("  REMU: {}", stats.mdu_remu);
#endif
    std::println("访存指令: {}", stats.memory_access_operation);
    std::println("CSR指令: {}", stats.control_status_register_operation);
    std::println("分支/跳转指令: {}", stats.branch_operation);
    std::println("  其中 jal指令: {}", stats.jal_operation);
    std::println("  其中 jalr指令: {}", stats.jalr_operation);
    std::println("  其中 条件分支: {}", ConditionalBranchCount(stats));
    const auto classified_instructions{ClassifiedInstructionCount(stats)};
    std::println("已分类EXU操作合计: {}", classified_instructions);
    std::println("  注: 该口径不含halt/fence/异常等未分类操作，不应与推测IFU取指强求相等");
#ifdef CONFIG_RV32_M
    if (stats.mdu_complete > 0)
    {
        std::println("MDU发射或在途活动: {} 周期, 每次完成摊销 {:.2f} 周期", stats.mdu_active_cycle,
            static_cast<double>(stats.mdu_active_cycle) / stats.mdu_complete);
        std::println("MDU等待计算结果: {} 周期, 每次完成摊销 {:.2f} 周期", stats.mdu_wait_cycle,
            static_cast<double>(stats.mdu_wait_cycle) / stats.mdu_complete);
        std::println("  注: 以上是队列占用/吞吐指标，流水化MDU下不等于单条指令延迟");
    }
#endif
    auto load_store_sum{stats.load_data + stats.store_data};
    std::println("LSU完成操作合计: {}", load_store_sum);
    std::println();
    std::println("指令类别占比(分母为已分类EXU操作，不是推测取指数):");
    if (classified_instructions > 0)
    {
        auto total_instructions = static_cast<double>(classified_instructions);
        std::println("ALU指令占比: {:.1f}%", 100.0 * stats.arithmetic_operation / total_instructions);
#ifdef CONFIG_RV32_M
        std::println("MDU指令占比: {:.1f}%", 100.0 * stats.mdu_complete / total_instructions);
#endif
        std::println("访存指令占比: {:.1f}%", 100.0 * stats.memory_access_operation / total_instructions);
        if (stats.memory_access_operation > 0)
        {
            std::println("访存类指令在EXU输入端驻留摊销: {:.2f} 周期/操作",
                static_cast<double>(stats.memory_access_operation_active_cycle) /
                    stats.memory_access_operation);
        }
        std::println("CSR指令占比: {:.1f}%", 100.0 * stats.control_status_register_operation / total_instructions);
        std::println("分支/跳转指令占比: {:.1f}%", 100.0 * stats.branch_operation / total_instructions);
    }
    std::println();
    std::println("IFU状态观测(各项可重叠，不可相加):");
    if (total_cycles > 0)
    {
        auto total {static_cast<double>(total_cycles)};
        std::println("响应有效但下游未接受: {} 周期, 占比 {:.1f}%", stats.instruction_fetch_stall_pipeline, 100.0 * stats.instruction_fetch_stall_pipeline / total);
        std::println("ICache refill AR/R状态合计: {} 周期, 占比 {:.1f}%", stats.instruction_fetch_stall_axi, 100.0 * stats.instruction_fetch_stall_axi / total);
        std::println("  ICache refill处于AR请求状态(含握手拍): {} 周期, 占比 {:.1f}%", stats.instruction_fetch_stall_ar, 100.0 * stats.instruction_fetch_stall_ar / total);
        std::println("  ICache refill处于R响应状态(含传输拍): {} 周期, 占比 {:.1f}%", stats.instruction_fetch_stall_r, 100.0 * stats.instruction_fetch_stall_r / total);
        std::println("控制流重定向事件: {} 次", stats.instruction_fetch_stall_redirect);
        std::println("IFU空闲(无取指请求): {} 周期, 占比 {:.1f}%", stats.instruction_fetch_stall_idle, 100.0 * stats.instruction_fetch_stall_idle / total);
    }
    std::println();
    std::println("EXU流水状态(各项可重叠):");
    if (total_cycles > 0)
    {
        auto total{static_cast<double>(total_cycles)};
        std::println("EXU下游/顺序等待(已排除MDU计算等待): {} 周期, 占比 {:.1f}%", stats.exu_stall_lsu, 100.0 * stats.exu_stall_lsu / total);
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
        std::println("  非load的未就绪依赖(主要含MDU/隐藏写者): {} 周期, 占比 {:.1f}%", stats.idu_stall_raw_alu, 100.0 * stats.idu_stall_raw_alu / total);
    }
    std::println();
    std::println("异常/中断提交: {} 次", stats.trap_count);
    std::println();
    std::println("LSU内存事务FSM占用(请求/响应/写缓冲等待，非端到端指令延迟):");
    auto load_store_total{stats.load_data + stats.store_data};
    if (load_store_total > 0)
    {
        std::println("平均FSM占用: {:.2f} 周期/完成操作", static_cast<double>(stats.load_store_unit_active_cycle) / load_store_total);
    }
    if (stats.load_data > 0)
    {
        std::println("  读FSM占用: {:.2f} 周期/完成 ({} 次)", static_cast<double>(stats.load_store_unit_load_active_cycle) / stats.load_data, stats.load_data);
    }
    if (stats.store_data > 0)
    {
        std::println("  写FSM占用: {:.2f} 周期/完成 ({} 次)", static_cast<double>(stats.load_store_unit_store_active_cycle) / stats.store_data, stats.store_data);
    }
    std::println();
    std::println("LSU状态占用分解(非纯AXI stall，B等待可后台重叠):");
    if (total_cycles > 0)
    {
        auto total{static_cast<double>(total_cycles)};
        std::println("  LSU读请求状态(含DCache hit内部握手): {} 周期, 占总周期 {:.1f}%", stats.lsu_stall_read_ar, 100.0 * stats.lsu_stall_read_ar / total);
        std::println("  LSU读响应状态(含DCache内部等待): {} 周期, 占总周期 {:.1f}%", stats.lsu_stall_read_r, 100.0 * stats.lsu_stall_read_r / total);
        std::println("  写缓冲无空位: {} 周期, 占总周期 {:.1f}%", stats.lsu_stall_write_req, 100.0 * stats.lsu_stall_write_req / total);
        std::println("  写响应在途(可后台重叠): {} 周期, 占总周期 {:.1f}%", stats.lsu_stall_write_b, 100.0 * stats.lsu_stall_write_b / total);
    }
    std::println();
#ifdef CONFIG_ICACHE
    std::println("ICache 性能:");
    std::println("  命中: {} 次", stats.icache_hit);
    std::println("  缺失: {} 次", stats.icache_miss);
    const auto icache{EstimateCachePerformance(
        stats.icache_hit,
        stats.icache_miss,
        stats.instruction_fetch_stall_ar + stats.instruction_fetch_stall_r)};
    if (icache.access_count > 0)
    {
        std::println("  命中率: {:.1f}%", 100.0 * icache.hit_rate);
        if (stats.icache_miss > 0)
            std::println("  refill平均服务周期: {:.1f}", icache.miss_service_cycle);
        std::println("  AMAT估算(hit基线=1): {:.1f} 周期", icache.amat);
    }
#endif
#ifdef CONFIG_DCACHE
    std::println("DCache 性能:");
    std::println("  load命中: {} 次", stats.dcache_hit);
    std::println("  可缓存load缺失: {} 次", stats.dcache_miss);
    const auto dcache{EstimateCachePerformance(
        stats.dcache_hit,
        stats.dcache_miss,
        stats.dcache_refill_req_cycle + stats.dcache_refill_resp_cycle)};
    if (dcache.access_count > 0)
    {
        std::println("  load命中率: {:.1f}%", 100.0 * dcache.hit_rate);
        std::println("  refill AR状态: {} 周期", stats.dcache_refill_req_cycle);
        std::println("  refill R状态: {} 周期", stats.dcache_refill_resp_cycle);
        if (stats.dcache_miss > 0)
            std::println("  每个可缓存miss的refill服务周期: {:.1f}", dcache.miss_service_cycle);
        std::println("  AMAT估算(hit基线=1): {:.1f} 周期", dcache.amat);
    }
#endif
}
#endif
