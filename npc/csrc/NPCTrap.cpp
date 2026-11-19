module;
module npc.NPCTrap;
import npc.Output;
import npc.config;
import npc.trace.itrace;
import npc.SimulationContext;
void NPCTrap::Halt(npc::XWord PC, std::uint32_t Code) noexcept
{
    SimulationContext::Active().Trap().Halt(PC, Code);
}
void NPCTrap::Stop(npc::XWord PC) noexcept
{
    Halt(PC, 1);
}
bool NPCTrap::HasHalted() noexcept
{
    return SimulationContext::Active().Trap().halted;
}
npc::XWord NPCTrap::GetPC() noexcept
{
    return SimulationContext::Active().Trap().pc;
}
std::uint32_t NPCTrap::GetCode() noexcept
{
    return SimulationContext::Active().Trap().code;
}
int NPCTrap::PrintResult(std::size_t Cycles, std::size_t Instructions)
{
    auto &context{SimulationContext::Active()};
    auto &out{context.Out()};
    auto &trap{context.Trap()};
    if (!trap.halted)
    {
        out.Error("NPC在未触发陷阱的情况下退出");
        return 1;
    }
    // 按 XLEN 补齐地址宽度，避免截断。
    const auto haltPC{std::format("0x{:0{}x}", trap.pc, npc::XHexDigits)};
    if (trap.code == 0)
    {
        if constexpr (npc::HasDicArchCounter)
        {
            const auto ipc{Cycles > 0
                               ? static_cast<double>(Instructions) /
                                     static_cast<double>(Cycles)
                               : 0.0};
            out.Info("HIT GOOD TRAP at pc = {0}, cycles = {1}, instructions = {2}, ipc = {3:.4f}",
                         haltPC, Cycles, Instructions, ipc);
        }
        else
        {
            out.Info("HIT GOOD TRAP at pc = {0}, cycles = {1}",
                         haltPC, Cycles);
        }
        return 0;
    }
    if constexpr (npc::HasDicArchCounter)
    {
        const auto ipc{Cycles > 0
                           ? static_cast<double>(Instructions) /
                                 static_cast<double>(Cycles)
                           : 0.0};
        out.Error("HIT BAD TRAP at pc = {0}, code = {1}, cycles = {2}, instructions = {3}, ipc = {4:.4f}",
                     haltPC, trap.code, Cycles, Instructions, ipc);
    }
    else
    {
        out.Error("HIT BAD TRAP at pc = {0}, code = {1}, cycles = {2}",
                     haltPC, trap.code, Cycles);
    }
    if constexpr (npc::HasIRingBuf)
    {
        PrintIringbuf(trap.pc, out);
    }
    return 1;
}
void NPCTrap::PrintDicArchCounterStatistics(
    const DicArchCounterStats &stats,
    std::size_t total_cycles,
    std::size_t retired_instructions)
{
    auto &out{SimulationContext::Active().Out()};
    out.Info("数字集成电路计算机体系结构性能计数器");
    out.Info("退休指令: {}", retired_instructions);
    out.Info("IFU交付指令响应(含后续可能冲刷的指令): {}", stats.instruction_fetch);
    out.Info("EXU输出握手: {}", stats.execution_complete);
    out.Info("LSU完成读操作(含故障完成): {}", stats.load_data);
    out.Info("LSU完成写操作(含故障完成): {}", stats.store_data);
    out.Info("ALU指令: {}", stats.arithmetic_operation);
    if constexpr (npc::HasM)
    {
    out.Info("MDU请求: {}", stats.mdu_request);
    out.Info("MDU完成: {}", stats.mdu_complete);
    out.Info("  MUL: {}", stats.mdu_mul);
    out.Info("  MULH: {}", stats.mdu_mulh);
    out.Info("  MULHSU: {}", stats.mdu_mulhsu);
    out.Info("  MULHU: {}", stats.mdu_mulhu);
    out.Info("  DIV: {}", stats.mdu_div);
    out.Info("  DIVU: {}", stats.mdu_divu);
    out.Info("  REM: {}", stats.mdu_rem);
    out.Info("  REMU: {}", stats.mdu_remu);
    }
    out.Info("访存指令: {}", stats.memory_access_operation);
    out.Info("CSR指令: {}", stats.control_status_register_operation);
    out.Info("分支/跳转指令: {}", stats.branch_operation);
    out.Info("  其中 jal指令: {}", stats.jal_operation);
    out.Info("  其中 jalr指令: {}", stats.jalr_operation);
    out.Info("  其中 条件分支: {}", ConditionalBranchCount(stats));
    const auto classified_instructions{ClassifiedInstructionCount(stats)};
    out.Info("已分类EXU操作合计: {}", classified_instructions);
    out.Info("  注: 该口径不含halt/fence/异常等未分类操作，不应与推测IFU取指强求相等");
    if constexpr (npc::HasM)
    {
        if (stats.mdu_complete > 0)
        {
            out.Info("MDU发射或在途活动: {} 周期, 每次完成摊销 {:.2f} 周期", stats.mdu_active_cycle,
                static_cast<double>(stats.mdu_active_cycle) / stats.mdu_complete);
            out.Info("MDU等待计算结果: {} 周期, 每次完成摊销 {:.2f} 周期", stats.mdu_wait_cycle,
                static_cast<double>(stats.mdu_wait_cycle) / stats.mdu_complete);
            out.Info("  注: 以上是队列占用/吞吐指标，流水化MDU下不等于单条指令延迟");
        }
    }
    auto load_store_sum{stats.load_data + stats.store_data};
    out.Info("LSU完成操作合计: {}", load_store_sum);
    out.Info("");
    out.Info("指令类别占比(分母为已分类EXU操作，不是推测取指数):");
    if (classified_instructions > 0)
    {
        auto total_instructions = static_cast<double>(classified_instructions);
        out.Info("ALU指令占比: {:.1f}%", 100.0 * stats.arithmetic_operation / total_instructions);
        if constexpr (npc::HasM)
        {
            out.Info("MDU指令占比: {:.1f}%", 100.0 * stats.mdu_complete / total_instructions);
        }
        out.Info("访存指令占比: {:.1f}%", 100.0 * stats.memory_access_operation / total_instructions);
        if (stats.memory_access_operation > 0)
        {
            out.Info("访存类指令在EXU输入端驻留摊销: {:.2f} 周期/操作",
                static_cast<double>(stats.memory_access_operation_active_cycle) /
                    stats.memory_access_operation);
        }
        out.Info("CSR指令占比: {:.1f}%", 100.0 * stats.control_status_register_operation / total_instructions);
        out.Info("分支/跳转指令占比: {:.1f}%", 100.0 * stats.branch_operation / total_instructions);
    }
    out.Info("");
    out.Info("IFU状态观测(各项可重叠，不可相加):");
    if (total_cycles > 0)
    {
        auto total {static_cast<double>(total_cycles)};
        out.Info("响应有效但下游未接受: {} 周期, 占比 {:.1f}%", stats.instruction_fetch_stall_pipeline, 100.0 * stats.instruction_fetch_stall_pipeline / total);
        out.Info("ICache refill AR/R状态合计: {} 周期, 占比 {:.1f}%", stats.instruction_fetch_stall_axi, 100.0 * stats.instruction_fetch_stall_axi / total);
        out.Info("  ICache refill处于AR请求状态(含握手拍): {} 周期, 占比 {:.1f}%", stats.instruction_fetch_stall_ar, 100.0 * stats.instruction_fetch_stall_ar / total);
        out.Info("  ICache refill处于R响应状态(含传输拍): {} 周期, 占比 {:.1f}%", stats.instruction_fetch_stall_r, 100.0 * stats.instruction_fetch_stall_r / total);
        out.Info("控制流重定向事件: {} 次", stats.instruction_fetch_stall_redirect);
        out.Info("IFU空闲(无取指请求): {} 周期, 占比 {:.1f}%", stats.instruction_fetch_stall_idle, 100.0 * stats.instruction_fetch_stall_idle / total);
    }
    out.Info("");
    out.Info("EXU流水状态(各项可重叠):");
    if (total_cycles > 0)
    {
        auto total{static_cast<double>(total_cycles)};
        out.Info("EXU下游/顺序等待(已排除MDU计算等待): {} 周期, 占比 {:.1f}%", stats.exu_stall_lsu, 100.0 * stats.exu_stall_lsu / total);
        out.Info("EXU空转无输入(上游供给不足): {} 周期, 占比 {:.1f}%", stats.exu_idle_noinput, 100.0 * stats.exu_idle_noinput / total);
        out.Info("EX/MEM等待槽占用(5级拆分买到的访存重叠): {} 周期, 占比 {:.1f}%", stats.mem_waitslot, 100.0 * stats.mem_waitslot / total);
    }
    out.Info("");
    out.Info("IDU数据冒险阻塞分析:");
    if (total_cycles > 0)
    {
        auto total{static_cast<double>(total_cycles)};
        out.Info("RAW阻塞合计: {} 周期, 占比 {:.1f}%", stats.idu_stall_raw, 100.0 * stats.idu_stall_raw / total);
        out.Info("  load-use(等LSU数据,转发无法消除): {} 周期, 占比 {:.1f}%", stats.idu_stall_raw_loaduse, 100.0 * stats.idu_stall_raw_loaduse / total);
        out.Info("  非load的未就绪依赖(主要含MDU/隐藏写者): {} 周期, 占比 {:.1f}%", stats.idu_stall_raw_alu, 100.0 * stats.idu_stall_raw_alu / total);
    }
    out.Info("");
    out.Info("异常/中断提交: {} 次", stats.trap_count);
    out.Info("");
    out.Info("分支预测:");
    out.Info("  BTB命中/缺失: {} / {}", stats.btb_hit, stats.btb_miss);
    out.Info("  JAL-BTB命中: {}", stats.jal_btb_hit);
    out.Info("  RAS命中: {}", stats.ras_hit);
    out.Info("  分支/跳转误预测: {}", stats.branch_mispredict);
    out.Info("  条件分支 taken/not-taken: {} / {}",
        stats.branch_taken, stats.branch_not_taken);
    out.Info("  RAS缺失: {}", stats.ras_miss);
    out.Info("  重定向 branch/fence.i/trap/mret: {} / {} / {} / {}",
        stats.redirect_branch, stats.redirect_fence_i,
        stats.redirect_trap, stats.redirect_mret);
    out.Info("");
    out.Info("MMU/TLB:");
    out.Info("  ITLB命中/缺失: {} / {}", stats.itlb_hit, stats.itlb_miss);
    out.Info("  DTLB命中/缺失: {} / {}", stats.dtlb_hit, stats.dtlb_miss);
    out.Info("  TLB回填/冲刷: {} / {}", stats.tlb_refill, stats.tlb_flush);
    out.Info("  PTW walk/活跃周期/page fault: {} / {} / {}",
        stats.ptw_walk, stats.ptw_walk_cycle, stats.page_fault);
    out.Info("  超级页/ASID命中: {} / {}",
        stats.tlb_superpage_hit, stats.tlb_asid_hit);
    out.Info("");
    out.Info("FPU:");
    out.Info("  请求/完成: {} / {}", stats.fpu_req, stats.fpu_done);
    out.Info("  忙碌/等待周期: {} / {}",
        stats.fpu_active_cycle, stats.fpu_stall_cycle);
    out.Info("  DIV/SQRT、FMA、fflags异常: {} / {} / {}",
        stats.fpu_divsqrt, stats.fpu_fma, stats.fpu_exception);
    out.Info("");
    out.Info("AXI:");
    out.Info("  AR/AW/R/W/B: {} / {} / {} / {} / {}",
        stats.axi_ar, stats.axi_aw, stats.axi_r, stats.axi_w, stats.axi_b);
    out.Info("  stall/error: {} / {}", stats.axi_stall_cycle, stats.axi_error);
    out.Info("");
    out.Info("指令细分:");
    out.Info("  load/store/AMO: {} / {} / {}",
        stats.load_op, stats.store_op, stats.amo_op);
    out.Info("  fence/RVC/system: {} / {} / {}",
        stats.fence_op, stats.rvc_inst, stats.system_op);
    out.Info("  非法指令: {}", stats.illegal_inst);
    out.Info("  流水线冲刷周期: {}", stats.flush_cycle);
    out.Info("  WFI 指令(按NOP执行): {}", stats.wfi_inst);
    out.Info("");
    out.Info("缓存细节:");
    out.Info("  ICache/DCache实际回填: {} / {}",
        stats.icache_refill, stats.dcache_refill);
    out.Info("  非缓存/MMIO完成访问: {}", stats.uncached_access);
    out.Info("  ICache/DCache行填充: {} / {}",
        stats.icache_line_fill, stats.dcache_line_fill);
    out.Info("  DCache写直达/store行更新: {}", stats.dcache_writeback);
    out.Info("");
    out.Info("中断:");
    out.Info("  timer/external: {} / {}", stats.timer_irq, stats.external_irq);
    out.Info("  实际进入中断: {}", stats.interrupt_taken);
    out.Info("  PLIC claim/complete: {} / {}",
        stats.plic_claim, stats.plic_complete);
    out.Info("  pending周期: {}", stats.interrupt_pending_cycle);
    if (stats.interrupt_latency_count > 0)
    {
        out.Info("  平均/最大 pending→trap 延迟: {:.2f} / {} 周期 ({} 次)",
            static_cast<double>(stats.interrupt_latency_cycle) /
                static_cast<double>(stats.interrupt_latency_count),
            stats.interrupt_latency_max, stats.interrupt_latency_count);
    }
    out.Info("");
    out.Info("写缓冲:");
    out.Info("  无空位/排空: {} / {}", stats.wbuf_full, stats.wbuf_drain);
    if (total_cycles > 0)
    {
        out.Info("  平均占用: {:.2f} 项",
            static_cast<double>(stats.wbuf_occupancy_cycle) /
                static_cast<double>(total_cycles));
    }
    out.Info("");
    out.Info("CSR 读写: {} / {}", stats.csr_read, stats.csr_write);
    out.Info("CSR 非法: {}", stats.csr_illegal);
    out.Info("MDU 活跃周期: mul={} div={}",
        stats.mul_active_cycle, stats.div_active_cycle);
    out.Info("MDU 除法实际迭代位数/提前结束: {} / {}",
        stats.div_iterations, stats.div_early_out);
    out.Info("队列/气泡:");
    if (total_cycles > 0)
    {
        out.Info("  EXU输入槽占用率: {:.1f}%",
            100.0 * static_cast<double>(stats.issue_occupancy_cycle) /
                static_cast<double>(total_cycles));
        out.Info("  EXU输出/退休槽占用率: {:.1f}%",
            100.0 * static_cast<double>(stats.retire_occupancy_cycle) /
                static_cast<double>(total_cycles));
        out.Info("  EXU输入气泡占比: {:.1f}%",
            100.0 * static_cast<double>(stats.bubble_cycle) /
                static_cast<double>(total_cycles));
    }
    out.Info("");
    out.Info("LSU内存事务FSM占用(请求/响应/写缓冲等待，非端到端指令延迟):");
    auto load_store_total{stats.load_data + stats.store_data};
    if (load_store_total > 0)
    {
        out.Info("平均FSM占用: {:.2f} 周期/完成操作", static_cast<double>(stats.load_store_unit_active_cycle) / load_store_total);
    }
    if (stats.load_data > 0)
    {
        out.Info("  读FSM占用: {:.2f} 周期/完成 ({} 次)", static_cast<double>(stats.load_store_unit_load_active_cycle) / stats.load_data, stats.load_data);
    }
    if (stats.store_data > 0)
    {
        out.Info("  写FSM占用: {:.2f} 周期/完成 ({} 次)", static_cast<double>(stats.load_store_unit_store_active_cycle) / stats.store_data, stats.store_data);
    }
    out.Info("");
    out.Info("LSU状态占用分解(非纯AXI stall，B等待可后台重叠):");
    if (total_cycles > 0)
    {
        auto total{static_cast<double>(total_cycles)};
        out.Info("  LSU读请求状态(含DCache hit内部握手): {} 周期, 占总周期 {:.1f}%", stats.lsu_stall_read_ar, 100.0 * stats.lsu_stall_read_ar / total);
        out.Info("  LSU读响应状态(含DCache内部等待): {} 周期, 占总周期 {:.1f}%", stats.lsu_stall_read_r, 100.0 * stats.lsu_stall_read_r / total);
        out.Info("  写缓冲无空位: {} 周期, 占总周期 {:.1f}%", stats.lsu_stall_write_req, 100.0 * stats.lsu_stall_write_req / total);
        out.Info("  写响应在途(可后台重叠): {} 周期, 占总周期 {:.1f}%", stats.lsu_stall_write_b, 100.0 * stats.lsu_stall_write_b / total);
    }
    out.Info("");
    if constexpr (npc::HasICache)
    {
    out.Info("ICache 性能:");
    out.Info("  命中: {} 次", stats.icache_hit);
    out.Info("  缺失: {} 次", stats.icache_miss);
    const auto icache{EstimateCachePerformance(
        stats.icache_hit,
        stats.icache_miss,
        stats.instruction_fetch_stall_ar + stats.instruction_fetch_stall_r)};
    if (icache.access_count > 0)
    {
        out.Info("  命中率: {:.1f}%", 100.0 * icache.hit_rate);
        if (stats.icache_miss > 0)
            out.Info("  refill平均服务周期: {:.1f}", icache.miss_service_cycle);
        out.Info("  AMAT估算(hit基线=1): {:.1f} 周期", icache.amat);
    }
    }
    if constexpr (npc::HasDCache)
    {
    out.Info("DCache 性能:");
    out.Info("  load命中: {} 次", stats.dcache_hit);
    out.Info("  可缓存load缺失: {} 次", stats.dcache_miss);
    const auto dcache{EstimateCachePerformance(
        stats.dcache_hit,
        stats.dcache_miss,
        stats.dcache_refill_req_cycle + stats.dcache_refill_resp_cycle)};
    if (dcache.access_count > 0)
    {
        out.Info("  load命中率: {:.1f}%", 100.0 * dcache.hit_rate);
        out.Info("  refill AR状态: {} 周期", stats.dcache_refill_req_cycle);
        out.Info("  refill R状态: {} 周期", stats.dcache_refill_resp_cycle);
        if (stats.dcache_miss > 0)
            out.Info("  每个可缓存miss的refill服务周期: {:.1f}", dcache.miss_service_cycle);
        out.Info("  AMAT估算(hit基线=1): {:.1f} 周期", dcache.amat);
    }
    }
}
