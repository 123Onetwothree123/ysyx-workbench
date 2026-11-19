module;
module npc.PerfCounterAccumulator;
import std;
import npc.config;
import npc.DicArchCounterStats;
namespace npc
{
namespace
{
template <bool PerfSignals::*Member>
std::size_t Bit(const PerfSignals &signals) noexcept
{
    return signals.*Member ? 1U : 0U;
}
template <unsigned PerfSignals::*Member>
std::size_t Count(const PerfSignals &signals) noexcept
{
    return signals.*Member;
}
std::size_t MduMul(const PerfSignals &s) noexcept
{
    return s.mdu_done && s.mdu_op == 0U ? 1U : 0U;
}
std::size_t MduMulh(const PerfSignals &s) noexcept
{
    return s.mdu_done && s.mdu_op == 1U ? 1U : 0U;
}
std::size_t MduMulhsu(const PerfSignals &s) noexcept
{
    return s.mdu_done && s.mdu_op == 2U ? 1U : 0U;
}
std::size_t MduMulhu(const PerfSignals &s) noexcept
{
    return s.mdu_done && s.mdu_op == 3U ? 1U : 0U;
}
std::size_t MduDiv(const PerfSignals &s) noexcept
{
    return s.mdu_done && s.mdu_op == 4U ? 1U : 0U;
}
std::size_t MduDivu(const PerfSignals &s) noexcept
{
    return s.mdu_done && s.mdu_op == 5U ? 1U : 0U;
}
std::size_t MduRem(const PerfSignals &s) noexcept
{
    return s.mdu_done && s.mdu_op == 6U ? 1U : 0U;
}
std::size_t MduRemu(const PerfSignals &s) noexcept
{
    return s.mdu_done && s.mdu_op == 7U ? 1U : 0U;
}
// 类型活跃周期再排除 MDU 在途周期。
bool ClassifyExu(const PerfSignals &s) noexcept
{
    return s.execution_active &&
           (!PerfDicArchCounterMduActive || !s.mdu_active);
}
std::size_t AluActive(const PerfSignals &s) noexcept
{
    return ClassifyExu(s) && s.alu_op ? 1U : 0U;
}
std::size_t MemActive(const PerfSignals &s) noexcept
{
    return ClassifyExu(s) && s.mem_op ? 1U : 0U;
}
std::size_t CsrActive(const PerfSignals &s) noexcept
{
    return ClassifyExu(s) && s.csr_op ? 1U : 0U;
}
std::size_t BranchActive(const PerfSignals &s) noexcept
{
    return ClassifyExu(s) && s.branch_op ? 1U : 0U;
}
std::size_t DivIterations(const PerfSignals &s) noexcept
{
    return s.div_done ? s.div_iterations : 0U;
}
std::size_t DivEarlyOut(const PerfSignals &s) noexcept
{
    return s.div_done && s.div_early_out ? 1U : 0U;
}
struct Descriptor
{
    bool enabled;
    std::size_t DicArchCounterStats::*field;
    std::size_t (*sample)(const PerfSignals &);
};
// enabled 来自构建期 Kconfig；sample 返回本拍增量。
constexpr Descriptor Descriptors[]{
    {PerfDicArchCounterIfuFetch, &DicArchCounterStats::instruction_fetch,
     &Bit<&PerfSignals::ifu_fetch>},
    {PerfDicArchCounterExuDone, &DicArchCounterStats::execution_complete,
     &Bit<&PerfSignals::exu_done>},
    {PerfDicArchCounterMduReq, &DicArchCounterStats::mdu_request,
     &Bit<&PerfSignals::mdu_req>},
    {PerfDicArchCounterMduDone, &DicArchCounterStats::mdu_complete,
     &Bit<&PerfSignals::mdu_done>},
    {PerfDicArchCounterMduDone && PerfDicArchCounterMduOp,
     &DicArchCounterStats::mdu_mul, &MduMul},
    {PerfDicArchCounterMduDone && PerfDicArchCounterMduOp,
     &DicArchCounterStats::mdu_mulh, &MduMulh},
    {PerfDicArchCounterMduDone && PerfDicArchCounterMduOp,
     &DicArchCounterStats::mdu_mulhsu, &MduMulhsu},
    {PerfDicArchCounterMduDone && PerfDicArchCounterMduOp,
     &DicArchCounterStats::mdu_mulhu, &MduMulhu},
    {PerfDicArchCounterMduDone && PerfDicArchCounterMduOp,
     &DicArchCounterStats::mdu_div, &MduDiv},
    {PerfDicArchCounterMduDone && PerfDicArchCounterMduOp,
     &DicArchCounterStats::mdu_divu, &MduDivu},
    {PerfDicArchCounterMduDone && PerfDicArchCounterMduOp,
     &DicArchCounterStats::mdu_rem, &MduRem},
    {PerfDicArchCounterMduDone && PerfDicArchCounterMduOp,
     &DicArchCounterStats::mdu_remu, &MduRemu},
    {PerfDicArchCounterMduActive, &DicArchCounterStats::mdu_active_cycle,
     &Bit<&PerfSignals::mdu_active>},
    {PerfDicArchCounterMduWait, &DicArchCounterStats::mdu_wait_cycle,
     &Bit<&PerfSignals::mdu_wait>},
    {PerfDicArchCounterLsuLoad, &DicArchCounterStats::load_data,
     &Bit<&PerfSignals::lsu_load>},
    {PerfDicArchCounterLsuStore, &DicArchCounterStats::store_data,
     &Bit<&PerfSignals::lsu_store>},
    {PerfDicArchCounterIfuStallPipeline,
     &DicArchCounterStats::instruction_fetch_stall_pipeline,
     &Bit<&PerfSignals::ifu_stall_pipeline>},
    {PerfDicArchCounterIfuStallAxi,
     &DicArchCounterStats::instruction_fetch_stall_axi,
     &Bit<&PerfSignals::ifu_stall_axi>},
    {PerfDicArchCounterIfuStallAr,
     &DicArchCounterStats::instruction_fetch_stall_ar,
     &Bit<&PerfSignals::ifu_stall_ar>},
    {PerfDicArchCounterIfuStallR,
     &DicArchCounterStats::instruction_fetch_stall_r,
     &Bit<&PerfSignals::ifu_stall_r>},
    {PerfDicArchCounterIfuStallRedirect,
     &DicArchCounterStats::instruction_fetch_stall_redirect,
     &Bit<&PerfSignals::ifu_stall_redirect>},
    {PerfDicArchCounterIfuStallIdle,
     &DicArchCounterStats::instruction_fetch_stall_idle,
     &Bit<&PerfSignals::ifu_stall_idle>},
    {PerfDicArchCounterExecutionActive,
     &DicArchCounterStats::execution_active_cycle,
     &Bit<&PerfSignals::execution_active>},
    {PerfDicArchCounterExuStallLsu, &DicArchCounterStats::exu_stall_lsu,
     &Bit<&PerfSignals::exu_stall_lsu>},
    {PerfDicArchCounterMemWaitslot, &DicArchCounterStats::mem_waitslot,
     &Bit<&PerfSignals::mem_waitslot>},
    {PerfDicArchCounterAluOp,
     &DicArchCounterStats::arithmetic_operation_active_cycle, &AluActive},
    {PerfDicArchCounterMemOp,
     &DicArchCounterStats::memory_access_operation_active_cycle, &MemActive},
    {PerfDicArchCounterCsrOp,
     &DicArchCounterStats::control_status_register_operation_active_cycle,
     &CsrActive},
    {PerfDicArchCounterBranchOp,
     &DicArchCounterStats::branch_operation_active_cycle, &BranchActive},
    {PerfDicArchCounterLsuActive,
     &DicArchCounterStats::load_store_unit_active_cycle,
     &Bit<&PerfSignals::lsu_active>},
    {PerfDicArchCounterLsuLoadActive,
     &DicArchCounterStats::load_store_unit_load_active_cycle,
     &Bit<&PerfSignals::lsu_load_active>},
    {PerfDicArchCounterLsuStoreActive,
     &DicArchCounterStats::load_store_unit_store_active_cycle,
     &Bit<&PerfSignals::lsu_store_active>},
    {PerfDicArchCounterLsuStallReadAr,
     &DicArchCounterStats::lsu_stall_read_ar,
     &Bit<&PerfSignals::lsu_stall_read_ar>},
    {PerfDicArchCounterLsuStallReadR,
     &DicArchCounterStats::lsu_stall_read_r,
     &Bit<&PerfSignals::lsu_stall_read_r>},
    {PerfDicArchCounterLsuStallWriteReq,
     &DicArchCounterStats::lsu_stall_write_req,
     &Bit<&PerfSignals::lsu_stall_write_req>},
    {PerfDicArchCounterLsuStallWriteB,
     &DicArchCounterStats::lsu_stall_write_b,
     &Bit<&PerfSignals::lsu_stall_write_b>},
    {PerfDicArchCounterIcacheHit, &DicArchCounterStats::icache_hit,
     &Bit<&PerfSignals::icache_hit>},
    {PerfDicArchCounterIcacheMiss, &DicArchCounterStats::icache_miss,
     &Bit<&PerfSignals::icache_miss>},
    {PerfDicArchCounterDcacheHit, &DicArchCounterStats::dcache_hit,
     &Bit<&PerfSignals::dcache_hit>},
    {PerfDicArchCounterDcacheMiss, &DicArchCounterStats::dcache_miss,
     &Bit<&PerfSignals::dcache_miss>},
    {PerfDicArchCounterDcacheRefillReq,
     &DicArchCounterStats::dcache_refill_req_cycle,
     &Bit<&PerfSignals::dcache_refill_req>},
    {PerfDicArchCounterDcacheRefillResp,
     &DicArchCounterStats::dcache_refill_resp_cycle,
     &Bit<&PerfSignals::dcache_refill_resp>},
    {PerfDicArchCounterIduStallRaw, &DicArchCounterStats::idu_stall_raw,
     &Bit<&PerfSignals::idu_stall_raw>},
    {PerfDicArchCounterIduStallRawLoaduse,
     &DicArchCounterStats::idu_stall_raw_loaduse,
     &Bit<&PerfSignals::idu_stall_raw_loaduse>},
    {PerfDicArchCounterIduStallRawAlu,
     &DicArchCounterStats::idu_stall_raw_alu,
     &Bit<&PerfSignals::idu_stall_raw_alu>},
    {PerfDicArchCounterExuIdleNoinput,
     &DicArchCounterStats::exu_idle_noinput,
     &Bit<&PerfSignals::exu_idle_noinput>},
    {PerfDicArchCounterTrapCount, &DicArchCounterStats::trap_count,
     &Bit<&PerfSignals::trap_valid>},
    {PerfDicArchCounterBtbHit, &DicArchCounterStats::btb_hit,
     &Bit<&PerfSignals::btb_hit>},
    {PerfDicArchCounterBtbMiss, &DicArchCounterStats::btb_miss,
     &Bit<&PerfSignals::btb_miss>},
    {PerfDicArchCounterJalBtbHit, &DicArchCounterStats::jal_btb_hit,
     &Bit<&PerfSignals::jal_btb_hit>},
    {PerfDicArchCounterRasHit, &DicArchCounterStats::ras_hit,
     &Bit<&PerfSignals::ras_hit>},
    {PerfDicArchCounterBranchMispredict,
     &DicArchCounterStats::branch_mispredict,
     &Bit<&PerfSignals::branch_mispredict>},
    {PerfDicArchCounterItlbHit, &DicArchCounterStats::itlb_hit,
     &Bit<&PerfSignals::itlb_hit>},
    {PerfDicArchCounterItlbMiss, &DicArchCounterStats::itlb_miss,
     &Bit<&PerfSignals::itlb_miss>},
    {PerfDicArchCounterDtlbHit, &DicArchCounterStats::dtlb_hit,
     &Bit<&PerfSignals::dtlb_hit>},
    {PerfDicArchCounterDtlbMiss, &DicArchCounterStats::dtlb_miss,
     &Bit<&PerfSignals::dtlb_miss>},
    {PerfDicArchCounterTlbRefill, &DicArchCounterStats::tlb_refill,
     &Bit<&PerfSignals::tlb_refill>},
    {PerfDicArchCounterFpuReq, &DicArchCounterStats::fpu_req,
     &Bit<&PerfSignals::fpu_req>},
    {PerfDicArchCounterFpuDone, &DicArchCounterStats::fpu_done,
     &Bit<&PerfSignals::fpu_done>},
    {PerfDicArchCounterAxiAr, &DicArchCounterStats::axi_ar,
     &Bit<&PerfSignals::axi_ar>},
    {PerfDicArchCounterAxiAw, &DicArchCounterStats::axi_aw,
     &Bit<&PerfSignals::axi_aw>},
    {PerfDicArchCounterAxiR, &DicArchCounterStats::axi_r,
     &Bit<&PerfSignals::axi_r>},
    {PerfDicArchCounterAxiW, &DicArchCounterStats::axi_w,
     &Bit<&PerfSignals::axi_w>},
    {PerfDicArchCounterAxiB, &DicArchCounterStats::axi_b,
     &Bit<&PerfSignals::axi_b>},
    {PerfDicArchCounterLoadOp, &DicArchCounterStats::load_op,
     &Bit<&PerfSignals::load_op>},
    {PerfDicArchCounterStoreOp, &DicArchCounterStats::store_op,
     &Bit<&PerfSignals::store_op>},
    {PerfDicArchCounterAmoOp, &DicArchCounterStats::amo_op,
     &Bit<&PerfSignals::amo_op>},
    {PerfDicArchCounterBranchTaken, &DicArchCounterStats::branch_taken,
     &Bit<&PerfSignals::branch_taken>},
    {PerfDicArchCounterBranchNotTaken, &DicArchCounterStats::branch_not_taken,
     &Bit<&PerfSignals::branch_not_taken>},
    {PerfDicArchCounterTlbFlush, &DicArchCounterStats::tlb_flush,
     &Bit<&PerfSignals::tlb_flush>},
    {PerfDicArchCounterFpuActive, &DicArchCounterStats::fpu_active_cycle,
     &Bit<&PerfSignals::fpu_active>},
    {PerfDicArchCounterFenceOp, &DicArchCounterStats::fence_op,
     &Bit<&PerfSignals::fence_op>},
    {PerfDicArchCounterRvcInst, &DicArchCounterStats::rvc_inst,
     &Bit<&PerfSignals::rvc_inst>},
    {PerfDicArchCounterSystemOp, &DicArchCounterStats::system_op,
     &Bit<&PerfSignals::system_op>},
    {PerfDicArchCounterIcacheRefill, &DicArchCounterStats::icache_refill,
     &Bit<&PerfSignals::icache_refill>},
    {PerfDicArchCounterDcacheRefill, &DicArchCounterStats::dcache_refill,
     &Bit<&PerfSignals::dcache_refill>},
    {PerfDicArchCounterTimerIrq, &DicArchCounterStats::timer_irq,
     &Bit<&PerfSignals::timer_irq>},
    {PerfDicArchCounterExternalIrq, &DicArchCounterStats::external_irq,
     &Bit<&PerfSignals::external_irq>},
    {PerfDicArchCounterInterruptTaken,
     &DicArchCounterStats::interrupt_taken,
     &Bit<&PerfSignals::interrupt_taken>},
    {PerfDicArchCounterWbufFull, &DicArchCounterStats::wbuf_full,
     &Bit<&PerfSignals::wbuf_full>},
    {PerfDicArchCounterWbufDrain, &DicArchCounterStats::wbuf_drain,
     &Bit<&PerfSignals::wbuf_drain>},
    {PerfDicArchCounterCsrRead, &DicArchCounterStats::csr_read,
     &Bit<&PerfSignals::csr_read>},
    {PerfDicArchCounterCsrWrite, &DicArchCounterStats::csr_write,
     &Bit<&PerfSignals::csr_write>},
    {PerfDicArchCounterMulActive, &DicArchCounterStats::mul_active_cycle,
     &Bit<&PerfSignals::mul_active>},
    {PerfDicArchCounterDivActive, &DicArchCounterStats::div_active_cycle,
     &Bit<&PerfSignals::div_active>},
    {PerfDicArchCounterPtwWalk, &DicArchCounterStats::ptw_walk,
     &Bit<&PerfSignals::ptw_walk>},
    {PerfDicArchCounterPtwWalkCycle, &DicArchCounterStats::ptw_walk_cycle,
     &Bit<&PerfSignals::ptw_walk_cycle>},
    {PerfDicArchCounterPageFault, &DicArchCounterStats::page_fault,
     &Bit<&PerfSignals::page_fault>},
    {PerfDicArchCounterRasMiss, &DicArchCounterStats::ras_miss,
     &Bit<&PerfSignals::ras_miss>},
    {PerfDicArchCounterRedirectBranch,
     &DicArchCounterStats::redirect_branch,
     &Bit<&PerfSignals::redirect_branch>},
    {PerfDicArchCounterRedirectFenceI,
     &DicArchCounterStats::redirect_fence_i,
     &Bit<&PerfSignals::redirect_fence_i>},
    {PerfDicArchCounterRedirectTrap, &DicArchCounterStats::redirect_trap,
     &Bit<&PerfSignals::redirect_trap>},
    {PerfDicArchCounterRedirectMret, &DicArchCounterStats::redirect_mret,
     &Bit<&PerfSignals::redirect_mret>},
    {PerfDicArchCounterFpuStall, &DicArchCounterStats::fpu_stall_cycle,
     &Bit<&PerfSignals::fpu_stall>},
    {PerfDicArchCounterIllegalInst, &DicArchCounterStats::illegal_inst,
     &Bit<&PerfSignals::illegal_inst>},
    {PerfDicArchCounterAxiStall, &DicArchCounterStats::axi_stall_cycle,
     &Bit<&PerfSignals::axi_stall>},
    {PerfDicArchCounterAxiError, &DicArchCounterStats::axi_error,
     &Bit<&PerfSignals::axi_error>},
    {PerfDicArchCounterFpuDivsqrt, &DicArchCounterStats::fpu_divsqrt,
     &Bit<&PerfSignals::fpu_divsqrt>},
    {PerfDicArchCounterFpuFma, &DicArchCounterStats::fpu_fma,
     &Bit<&PerfSignals::fpu_fma>},
    {PerfDicArchCounterFpuException, &DicArchCounterStats::fpu_exception,
     &Bit<&PerfSignals::fpu_exception>},
    {PerfDicArchCounterCsrIllegal, &DicArchCounterStats::csr_illegal,
     &Bit<&PerfSignals::csr_illegal>},
    {PerfDicArchCounterPlicClaim, &DicArchCounterStats::plic_claim,
     &Bit<&PerfSignals::plic_claim>},
    {PerfDicArchCounterPlicComplete, &DicArchCounterStats::plic_complete,
     &Bit<&PerfSignals::plic_complete>},
    {PerfDicArchCounterFlushCycle, &DicArchCounterStats::flush_cycle,
     &Bit<&PerfSignals::flush_cycle>},
    {PerfDicArchCounterUncachedAccess,
     &DicArchCounterStats::uncached_access,
     &Bit<&PerfSignals::uncached_access>},
    {PerfDicArchCounterWbufOccupancy,
     &DicArchCounterStats::wbuf_occupancy_cycle,
     &Count<&PerfSignals::wbuf_count>},
    {PerfDicArchCounterTlbSuperpageHit,
     &DicArchCounterStats::tlb_superpage_hit,
     &Bit<&PerfSignals::tlb_superpage_hit>},
    {PerfDicArchCounterTlbAsidHit,
     &DicArchCounterStats::tlb_asid_hit,
     &Bit<&PerfSignals::tlb_asid_hit>},
    {PerfDicArchCounterIcacheLineFill,
     &DicArchCounterStats::icache_line_fill,
     &Bit<&PerfSignals::icache_line_fill>},
    {PerfDicArchCounterDcacheLineFill,
     &DicArchCounterStats::dcache_line_fill,
     &Bit<&PerfSignals::dcache_line_fill>},
    {PerfDicArchCounterDcacheWriteback,
     &DicArchCounterStats::dcache_writeback,
     &Bit<&PerfSignals::dcache_writeback>},
    {PerfDicArchCounterInterruptPendingCycle,
     &DicArchCounterStats::interrupt_pending_cycle,
     &Bit<&PerfSignals::irq_pending>},
    {PerfDicArchCounterWfiInst, &DicArchCounterStats::wfi_inst,
     &Bit<&PerfSignals::wfi_inst>},
    {PerfDicArchCounterIssueOccupancy,
     &DicArchCounterStats::issue_occupancy_cycle,
     &Bit<&PerfSignals::issue_occupancy>},
    {PerfDicArchCounterRetireOccupancy,
     &DicArchCounterStats::retire_occupancy_cycle,
     &Bit<&PerfSignals::retire_occupancy>},
    {PerfDicArchCounterBubbleCycle, &DicArchCounterStats::bubble_cycle,
     &Bit<&PerfSignals::bubble_cycle>},
    {PerfDicArchCounterDivIterations, &DicArchCounterStats::div_iterations,
     &DivIterations},
    {PerfDicArchCounterDivEarlyOut, &DicArchCounterStats::div_early_out,
     &DivEarlyOut},
};
}
void PerfCounterAccumulator::Reset()
{
    stats_ = {};
}
void PerfCounterAccumulator::Sample(const PerfSignals &signals)
{
    for (const auto &descriptor : Descriptors)
    {
        if (descriptor.enabled)
        {
            stats_.*descriptor.field += descriptor.sample(signals);
        }
    }
    // 事件分类按单计数器开关合取。
    unsigned event_mask = 0;
    if (PerfDicArchCounterAluOp) event_mask |= PerfEventALU;
    if (PerfDicArchCounterMemOp) event_mask |= PerfEventMEM;
    if (PerfDicArchCounterCsrOp) event_mask |= PerfEventCSR;
    if (PerfDicArchCounterBranchOp) event_mask |= PerfEventBRANCH;
    if (PerfDicArchCounterJalOp) event_mask |= PerfEventJAL;
    if (PerfDicArchCounterJalrOp) event_mask |= PerfEventJALR;
    RecordExecutionEvent(stats_, signals.exu_event_kind, event_mask);
    if (PerfDicArchCounterInterruptLatency)
    {
        const bool pending = signals.irq_pending;
        const bool taken = signals.interrupt_taken;
        if (taken)
        {
            if (irq_latency_active_)
            {
                stats_.interrupt_latency_cycle += irq_latency_current_;
                stats_.interrupt_latency_max =
                    std::max(stats_.interrupt_latency_max, irq_latency_current_);
                ++stats_.interrupt_latency_count;
            }
            irq_latency_active_ = false;
            irq_latency_current_ = 0;
        }
        else if (pending)
        {
            if (!irq_latency_active_)
            {
                irq_latency_active_ = true;
                irq_latency_current_ = 0;
            }
            else
            {
                ++irq_latency_current_;
            }
        }
        else
        {
            irq_latency_active_ = false;
        }
    }
}
const DicArchCounterStats &PerfCounterAccumulator::Stats() const noexcept
{
    return stats_;
}
DicArchCounterStats &PerfCounterAccumulator::Stats() noexcept
{
    return stats_;
}
}
