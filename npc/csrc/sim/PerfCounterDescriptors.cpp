module;
module npc.PerfCounterDescriptors;
import std;
import npc.config;
import npc.DicArchCounterStats;

namespace npc
{
namespace
{
using Stats = DicArchCounterStats;

struct Builder
{
    const Stats &stats;
    DicArchCounterStatisticsReport report;

    [[nodiscard]] std::size_t Value(std::size_t Stats::*field)
    {
        const auto index{CounterDescriptorIndex(field)};
        if (index < CounterDescriptorCount)
            report.printed[index] = true;
        return stats.*field;
    }

    [[nodiscard]] static std::string_view Label(std::size_t Stats::*field)
    {
        return CounterDescriptorFor(field).print_label;
    }

    void Line(std::string line) { report.lines.push_back(std::move(line)); }
    void Blank() { report.lines.emplace_back(); }

    void Group(PrintGroup group)
    {
        const auto title{PrintGroupTitle(group)};
        if (!title.empty())
            report.lines.emplace_back(title);
    }

    // 单字段或多字段共用一行，字段值以 " / " 分隔。多字段行沿用基线的
    // 合并标签（例如 "BTB命中/缺失"），单字段行使用 descriptor 的标签。
    template <typename... Members>
    void Counters(std::string_view line_label, Members... members)
    {
        std::string line{line_label};
        line += ": ";
        bool first{true};
        const auto append = [&](std::size_t value) {
            if (!first)
                line += " / ";
            first = false;
            line += std::to_string(value);
        };
        (append(Value(members)), ...);
        Line(std::move(line));
    }

    void CycleShare(
        std::string_view line_label,
        std::size_t Stats::*field,
        std::size_t total_cycles)
    {
        const auto value{Value(field)};
        Line(std::format(
            "{}: {} 周期, 占比 {:.1f}%",
            line_label,
            value,
            100.0 * static_cast<double>(value) /
                static_cast<double>(total_cycles)));
    }

    void CycleShareOfTotal(
        std::string_view line_label,
        std::size_t Stats::*field,
        std::size_t total_cycles)
    {
        const auto value{Value(field)};
        Line(std::format(
            "{}: {} 周期, 占总周期 {:.1f}%",
            line_label,
            value,
            100.0 * static_cast<double>(value) /
                static_cast<double>(total_cycles)));
    }

    void PercentOf(
        std::string_view line_label,
        std::size_t Stats::*field,
        double denominator)
    {
        Line(std::format(
            "{}: {:.1f}%",
            line_label,
            100.0 * static_cast<double>(Value(field)) / denominator));
    }
};
}

DicArchCounterStatisticsReport FormatDicArchCounterStatistics(
    const DicArchCounterStats &stats,
    std::size_t total_cycles,
    std::size_t retired_instructions)
{
    Builder b{stats, {}};
    b.Group(PrintGroup::ReportTitle);
    b.Line(std::format("退休指令: {}", retired_instructions));
    b.Counters(b.Label(&Stats::instruction_fetch), &Stats::instruction_fetch);
    b.Counters(b.Label(&Stats::execution_complete), &Stats::execution_complete);
    b.Counters(b.Label(&Stats::load_data), &Stats::load_data);
    b.Counters(b.Label(&Stats::store_data), &Stats::store_data);
    b.Counters(b.Label(&Stats::arithmetic_operation),
               &Stats::arithmetic_operation);
    if constexpr (npc::HasM)
    {
        b.Counters(b.Label(&Stats::mdu_request), &Stats::mdu_request);
        b.Counters(b.Label(&Stats::mdu_complete), &Stats::mdu_complete);
        b.Counters(b.Label(&Stats::mdu_mul), &Stats::mdu_mul);
        b.Counters(b.Label(&Stats::mdu_mulh), &Stats::mdu_mulh);
        b.Counters(b.Label(&Stats::mdu_mulhsu), &Stats::mdu_mulhsu);
        b.Counters(b.Label(&Stats::mdu_mulhu), &Stats::mdu_mulhu);
        b.Counters(b.Label(&Stats::mdu_div), &Stats::mdu_div);
        b.Counters(b.Label(&Stats::mdu_divu), &Stats::mdu_divu);
        b.Counters(b.Label(&Stats::mdu_rem), &Stats::mdu_rem);
        b.Counters(b.Label(&Stats::mdu_remu), &Stats::mdu_remu);
    }
    b.Counters(b.Label(&Stats::memory_access_operation),
               &Stats::memory_access_operation);
    b.Counters(b.Label(&Stats::control_status_register_operation),
               &Stats::control_status_register_operation);
    b.Counters(b.Label(&Stats::branch_operation), &Stats::branch_operation);
    b.Counters(b.Label(&Stats::jal_operation), &Stats::jal_operation);
    b.Counters(b.Label(&Stats::jalr_operation), &Stats::jalr_operation);
    b.Line(std::format("  其中 条件分支: {}", ConditionalBranchCount(stats)));
    const auto classified_instructions{ClassifiedInstructionCount(stats)};
    b.Line(std::format("已分类EXU操作合计: {}", classified_instructions));
    b.Line("  注: 该口径不含halt/fence/异常等未分类操作，不应与推测IFU取指强求相等");
    if constexpr (npc::HasM)
    {
        if (stats.mdu_complete > 0)
        {
            b.Line(std::format(
                "{}: {} 周期, 每次完成摊销 {:.2f} 周期",
                b.Label(&Stats::mdu_active_cycle),
                b.Value(&Stats::mdu_active_cycle),
                static_cast<double>(stats.mdu_active_cycle) /
                    static_cast<double>(stats.mdu_complete)));
            b.Line(std::format(
                "{}: {} 周期, 每次完成摊销 {:.2f} 周期",
                b.Label(&Stats::mdu_wait_cycle),
                b.Value(&Stats::mdu_wait_cycle),
                static_cast<double>(stats.mdu_wait_cycle) /
                    static_cast<double>(stats.mdu_complete)));
            b.Line("  注: 以上是队列占用/吞吐指标，流水化MDU下不等于单条指令延迟");
        }
    }
    const auto load_store_sum{
        b.Value(&Stats::load_data) + b.Value(&Stats::store_data)};
    b.Line(std::format("LSU完成操作合计: {}", load_store_sum));
    b.Blank();
    b.Group(PrintGroup::Ratio);
    if (classified_instructions > 0)
    {
        const auto total{static_cast<double>(classified_instructions)};
        b.PercentOf(CounterShareLabel(&Stats::arithmetic_operation),
                    &Stats::arithmetic_operation, total);
        if constexpr (npc::HasM)
        {
            b.PercentOf(CounterShareLabel(&Stats::mdu_complete), &Stats::mdu_complete,
                        total);
        }
        b.PercentOf(CounterShareLabel(&Stats::memory_access_operation),
                    &Stats::memory_access_operation, total);
        if (stats.memory_access_operation > 0)
        {
            const auto active{b.Value(&Stats::memory_access_operation_active_cycle)};
            b.Line(std::format(
                "访存类指令在EXU输入端驻留摊销: {:.2f} 周期/操作",
                static_cast<double>(active) /
                    static_cast<double>(stats.memory_access_operation)));
        }
        b.PercentOf(CounterShareLabel(&Stats::control_status_register_operation),
                    &Stats::control_status_register_operation, total);
        b.PercentOf(CounterShareLabel(&Stats::branch_operation),
                    &Stats::branch_operation, total);
    }
    b.Blank();
    b.Group(PrintGroup::Ifu);
    if (total_cycles > 0)
    {
        b.CycleShare(b.Label(&Stats::instruction_fetch_stall_pipeline),
                     &Stats::instruction_fetch_stall_pipeline, total_cycles);
        b.CycleShare(b.Label(&Stats::instruction_fetch_stall_axi),
                     &Stats::instruction_fetch_stall_axi, total_cycles);
        b.CycleShare(b.Label(&Stats::instruction_fetch_stall_ar),
                     &Stats::instruction_fetch_stall_ar, total_cycles);
        b.CycleShare(b.Label(&Stats::instruction_fetch_stall_r),
                     &Stats::instruction_fetch_stall_r, total_cycles);
        b.Line(std::format(
            "{}: {} 次", b.Label(&Stats::instruction_fetch_stall_redirect),
            b.Value(&Stats::instruction_fetch_stall_redirect)));
        b.CycleShare(b.Label(&Stats::instruction_fetch_stall_idle),
                     &Stats::instruction_fetch_stall_idle, total_cycles);
    }
    b.Blank();
    b.Group(PrintGroup::Exu);
    if (total_cycles > 0)
    {
        b.CycleShare(b.Label(&Stats::exu_stall_lsu), &Stats::exu_stall_lsu,
                     total_cycles);
        b.CycleShare(b.Label(&Stats::exu_idle_noinput),
                     &Stats::exu_idle_noinput, total_cycles);
        b.CycleShare(b.Label(&Stats::mem_waitslot), &Stats::mem_waitslot,
                     total_cycles);
    }
    b.Blank();
    b.Group(PrintGroup::Idu);
    if (total_cycles > 0)
    {
        b.CycleShare(b.Label(&Stats::idu_stall_raw), &Stats::idu_stall_raw,
                     total_cycles);
        b.CycleShare(b.Label(&Stats::idu_stall_raw_loaduse),
                     &Stats::idu_stall_raw_loaduse, total_cycles);
        b.CycleShare(b.Label(&Stats::idu_stall_raw_alu),
                     &Stats::idu_stall_raw_alu, total_cycles);
    }
    b.Blank();
    b.Group(PrintGroup::Trap);
    b.Line(std::format(
        "{}: {} 次", b.Label(&Stats::trap_count), b.Value(&Stats::trap_count)));
    b.Blank();
    b.Group(PrintGroup::BranchPrediction);
    b.Counters("  BTB命中/缺失", &Stats::btb_hit, &Stats::btb_miss);
    b.Counters(b.Label(&Stats::jal_btb_hit), &Stats::jal_btb_hit);
    b.Counters(b.Label(&Stats::ras_hit), &Stats::ras_hit);
    b.Counters(b.Label(&Stats::branch_mispredict), &Stats::branch_mispredict);
    b.Counters(
        "  条件分支 taken/not-taken", &Stats::branch_taken,
        &Stats::branch_not_taken);
    b.Counters(b.Label(&Stats::ras_miss), &Stats::ras_miss);
    b.Counters(
        "  重定向 branch/fence.i/trap/mret", &Stats::redirect_branch,
        &Stats::redirect_fence_i, &Stats::redirect_trap,
        &Stats::redirect_mret);
    b.Blank();
    b.Group(PrintGroup::MmuTlb);
    b.Counters("  ITLB命中/缺失", &Stats::itlb_hit, &Stats::itlb_miss);
    b.Counters("  DTLB命中/缺失", &Stats::dtlb_hit, &Stats::dtlb_miss);
    b.Counters("  TLB回填/冲刷", &Stats::tlb_refill, &Stats::tlb_flush);
    b.Counters(
        "  PTW walk/活跃周期/page fault", &Stats::ptw_walk,
        &Stats::ptw_walk_cycle, &Stats::page_fault);
    b.Counters(
        "  超级页/ASID命中", &Stats::tlb_superpage_hit,
        &Stats::tlb_asid_hit);
    b.Blank();
    b.Group(PrintGroup::Fpu);
    b.Counters("  请求/完成", &Stats::fpu_req, &Stats::fpu_done);
    b.Counters(
        "  忙碌/等待周期", &Stats::fpu_active_cycle, &Stats::fpu_stall_cycle);
    b.Counters(
        "  DIV/SQRT、FMA、fflags异常", &Stats::fpu_divsqrt, &Stats::fpu_fma,
        &Stats::fpu_exception);
    b.Blank();
    b.Group(PrintGroup::Axi);
    b.Counters(
        "  AR/AW/R/W/B", &Stats::axi_ar, &Stats::axi_aw, &Stats::axi_r,
        &Stats::axi_w, &Stats::axi_b);
    b.Counters(
        "  stall/error", &Stats::axi_stall_cycle, &Stats::axi_error);
    b.Blank();
    b.Group(PrintGroup::InstructionMix);
    b.Counters(
        "  load/store/AMO", &Stats::load_op, &Stats::store_op,
        &Stats::amo_op);
    b.Counters(
        "  fence/RVC/system", &Stats::fence_op, &Stats::rvc_inst,
        &Stats::system_op);
    b.Counters(b.Label(&Stats::illegal_inst), &Stats::illegal_inst);
    b.Counters(b.Label(&Stats::flush_cycle), &Stats::flush_cycle);
    b.Counters(b.Label(&Stats::wfi_inst), &Stats::wfi_inst);
    b.Blank();
    b.Group(PrintGroup::CacheDetail);
    b.Counters(
        "  ICache/DCache实际回填", &Stats::icache_refill,
        &Stats::dcache_refill);
    b.Counters(b.Label(&Stats::uncached_access), &Stats::uncached_access);
    b.Counters(
        "  ICache/DCache行填充", &Stats::icache_line_fill,
        &Stats::dcache_line_fill);
    b.Counters(b.Label(&Stats::dcache_writeback), &Stats::dcache_writeback);
    b.Blank();
    b.Group(PrintGroup::Interrupt);
    b.Counters("  timer/external", &Stats::timer_irq, &Stats::external_irq);
    b.Counters(b.Label(&Stats::interrupt_taken), &Stats::interrupt_taken);
    b.Counters("  PLIC claim/complete", &Stats::plic_claim, &Stats::plic_complete);
    b.Counters(
        b.Label(&Stats::interrupt_pending_cycle),
        &Stats::interrupt_pending_cycle);
    if (stats.interrupt_latency_count > 0)
    {
        b.Line(std::format(
            "  平均/最大 pending→trap 延迟: {:.2f} / {} 周期 ({} 次)",
            static_cast<double>(b.Value(&Stats::interrupt_latency_cycle)) /
                static_cast<double>(stats.interrupt_latency_count),
            b.Value(&Stats::interrupt_latency_max),
            b.Value(&Stats::interrupt_latency_count)));
    }
    b.Blank();
    b.Group(PrintGroup::WriteBuffer);
    b.Counters("  无空位/排空", &Stats::wbuf_full, &Stats::wbuf_drain);
    if (total_cycles > 0)
    {
        b.Line(std::format(
            "  平均占用: {:.2f} 项",
            static_cast<double>(b.Value(&Stats::wbuf_occupancy_cycle)) /
                static_cast<double>(total_cycles)));
    }
    b.Blank();
    b.Group(PrintGroup::CsrMdu);
    b.Counters("CSR 读写", &Stats::csr_read, &Stats::csr_write);
    b.Counters(b.Label(&Stats::csr_illegal), &Stats::csr_illegal);
    b.Line(std::format(
        "MDU 活跃周期: mul={} div={}", b.Value(&Stats::mul_active_cycle),
        b.Value(&Stats::div_active_cycle)));
    b.Counters(
        "MDU 除法实际迭代位数/提前结束", &Stats::div_iterations,
        &Stats::div_early_out);
    b.Group(PrintGroup::QueueBubble);
    if (total_cycles > 0)
    {
        const auto total{static_cast<double>(total_cycles)};
        b.PercentOf(b.Label(&Stats::issue_occupancy_cycle),
                    &Stats::issue_occupancy_cycle, total);
        b.PercentOf(b.Label(&Stats::retire_occupancy_cycle),
                    &Stats::retire_occupancy_cycle, total);
        b.PercentOf(b.Label(&Stats::bubble_cycle), &Stats::bubble_cycle,
                    total);
    }
    b.Blank();
    b.Group(PrintGroup::LsuFsm);
    if (load_store_sum > 0)
    {
        b.Line(std::format(
            "平均FSM占用: {:.2f} 周期/完成操作",
            static_cast<double>(b.Value(&Stats::load_store_unit_active_cycle)) /
                static_cast<double>(load_store_sum)));
    }
    if (stats.load_data > 0)
    {
        b.Line(std::format(
            "  读FSM占用: {:.2f} 周期/完成 ({} 次)",
            static_cast<double>(b.Value(&Stats::load_store_unit_load_active_cycle)) /
                static_cast<double>(stats.load_data),
            stats.load_data));
    }
    if (stats.store_data > 0)
    {
        b.Line(std::format(
            "  写FSM占用: {:.2f} 周期/完成 ({} 次)",
            static_cast<double>(b.Value(&Stats::load_store_unit_store_active_cycle)) /
                static_cast<double>(stats.store_data),
            stats.store_data));
    }
    b.Blank();
    b.Group(PrintGroup::LsuState);
    if (total_cycles > 0)
    {
        const auto total{static_cast<double>(total_cycles)};
        b.CycleShareOfTotal(b.Label(&Stats::lsu_stall_read_ar),
                          &Stats::lsu_stall_read_ar, total_cycles);
        b.CycleShareOfTotal(b.Label(&Stats::lsu_stall_read_r),
                          &Stats::lsu_stall_read_r, total_cycles);
        b.CycleShareOfTotal(b.Label(&Stats::lsu_stall_write_req),
                          &Stats::lsu_stall_write_req, total_cycles);
        b.CycleShareOfTotal(b.Label(&Stats::lsu_stall_write_b),
                          &Stats::lsu_stall_write_b, total_cycles);
        (void)total;
    }
    b.Blank();
    if constexpr (npc::HasICache)
    {
        b.Group(PrintGroup::ICache);
        b.Line(std::format("  命中: {} 次", b.Value(&Stats::icache_hit)));
        b.Line(std::format("  缺失: {} 次", b.Value(&Stats::icache_miss)));
        const auto icache{EstimateCachePerformance(
            stats.icache_hit, stats.icache_miss,
            stats.instruction_fetch_stall_ar +
                stats.instruction_fetch_stall_r)};
        if (icache.access_count > 0)
        {
            b.Line(std::format("  命中率: {:.1f}%", 100.0 * icache.hit_rate));
            if (stats.icache_miss > 0)
            {
                b.Line(std::format(
                    "  refill平均服务周期: {:.1f}",
                    icache.miss_service_cycle));
            }
            b.Line(std::format(
                "  AMAT估算(hit基线=1): {:.1f} 周期", icache.amat));
        }
    }
    if constexpr (npc::HasDCache)
    {
        b.Group(PrintGroup::DCache);
        b.Line(std::format("  load命中: {} 次", b.Value(&Stats::dcache_hit)));
        b.Line(std::format(
            "  可缓存load缺失: {} 次", b.Value(&Stats::dcache_miss)));
        const auto dcache{EstimateCachePerformance(
            stats.dcache_hit, stats.dcache_miss,
            stats.dcache_refill_req_cycle + stats.dcache_refill_resp_cycle)};
        if (dcache.access_count > 0)
        {
            b.Line(std::format(
                "  load命中率: {:.1f}%", 100.0 * dcache.hit_rate));
            b.Line(std::format(
                "  refill AR状态: {} 周期",
                b.Value(&Stats::dcache_refill_req_cycle)));
            b.Line(std::format(
                "  refill R状态: {} 周期",
                b.Value(&Stats::dcache_refill_resp_cycle)));
            if (stats.dcache_miss > 0)
            {
                b.Line(std::format(
                    "  每个可缓存miss的refill服务周期: {:.1f}",
                    dcache.miss_service_cycle));
            }
            b.Line(std::format(
                "  AMAT估算(hit基线=1): {:.1f} 周期", dcache.amat));
        }
    }
    return std::move(b.report);
}
}
