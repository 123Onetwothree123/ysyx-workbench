export module npc.DicArchCounterStats;
import std;

export struct DicArchCounterStats
{
    std::size_t instruction_fetch{0};
    std::size_t execution_complete{0};
    std::size_t load_data{0};
    std::size_t store_data{0};
    std::size_t arithmetic_operation{0};
    std::size_t mdu_request{0};
    std::size_t mdu_complete{0};
    std::size_t mdu_mul{0};
    std::size_t mdu_mulh{0};
    std::size_t mdu_mulhsu{0};
    std::size_t mdu_mulhu{0};
    std::size_t mdu_div{0};
    std::size_t mdu_divu{0};
    std::size_t mdu_rem{0};
    std::size_t mdu_remu{0};
    std::size_t mdu_active_cycle{0};
    std::size_t mdu_wait_cycle{0};
    std::size_t memory_access_operation{0};
    std::size_t control_status_register_operation{0};
    std::size_t branch_operation{0};
    std::size_t jal_operation{0};
    std::size_t jalr_operation{0};
    std::size_t instruction_fetch_stall_pipeline{0};
    // 历史字段名保留给 CSV/调用方；实际语义是 ICache refill AR/R
    // 状态的并集，不是任意 FetchReady 反压。
    std::size_t instruction_fetch_stall_axi{0};
    std::size_t instruction_fetch_stall_ar{0};
    std::size_t instruction_fetch_stall_r{0};
    std::size_t instruction_fetch_stall_redirect{0};
    std::size_t instruction_fetch_stall_idle{0};
    std::size_t execution_active_cycle{0};
    // EXU 无输出握手且已排除 MDU 计算等待；包括下游反压和精确顺序排空。
    std::size_t exu_stall_lsu{0};
    std::size_t arithmetic_operation_active_cycle{0};
    std::size_t memory_access_operation_active_cycle{0};
    std::size_t control_status_register_operation_active_cycle{0};
    std::size_t branch_operation_active_cycle{0};
    std::size_t load_store_unit_active_cycle{0};
    std::size_t load_store_unit_load_active_cycle{0};
    std::size_t load_store_unit_store_active_cycle{0};
    std::size_t lsu_stall_read_ar{0};
    std::size_t lsu_stall_read_r{0};
    std::size_t lsu_stall_write_req{0};
    std::size_t lsu_stall_write_b{0};
    std::size_t icache_hit{0};
    std::size_t icache_miss{0};
    std::size_t dcache_hit{0};
    std::size_t dcache_miss{0};
    // DCache 自己的 refill 状态占用。与 LSU 的读请求/响应状态分开记录，
    // 后者也覆盖 cache hit 和非缓存访问，不能作为 miss service time。
    std::size_t dcache_refill_req_cycle{0};
    std::size_t dcache_refill_resp_cycle{0};
    std::size_t idu_stall_raw{0};
    std::size_t idu_stall_raw_loaduse{0};
    // 非 load 的未就绪 RAW；主要是 MDU/隐藏写者，不代表普通 ALU 可转发依赖。
    std::size_t idu_stall_raw_alu{0};
    std::size_t exu_idle_noinput{0};
    std::size_t trap_count{0};
    std::size_t mem_waitslot{0};
    std::size_t btb_hit{0};
    std::size_t btb_miss{0};
    std::size_t jal_btb_hit{0};
    std::size_t ras_hit{0};
    std::size_t branch_mispredict{0};
    std::size_t itlb_hit{0};
    std::size_t itlb_miss{0};
    std::size_t dtlb_hit{0};
    std::size_t dtlb_miss{0};
    std::size_t tlb_refill{0};
    std::size_t fpu_req{0};
    std::size_t fpu_done{0};
    std::size_t axi_ar{0};
    std::size_t axi_aw{0};
    std::size_t axi_r{0};
    std::size_t axi_w{0};
    std::size_t axi_b{0};
    std::size_t load_op{0};
    std::size_t store_op{0};
    std::size_t amo_op{0};
    std::size_t branch_taken{0};
    std::size_t branch_not_taken{0};
    std::size_t tlb_flush{0};
    std::size_t fpu_active_cycle{0};
    std::size_t fence_op{0};
    std::size_t rvc_inst{0};
    std::size_t system_op{0};
    std::size_t icache_refill{0};
    std::size_t dcache_refill{0};
    std::size_t timer_irq{0};
    std::size_t external_irq{0};
    std::size_t interrupt_taken{0};
    std::size_t wbuf_full{0};
    std::size_t wbuf_drain{0};
    std::size_t csr_read{0};
    std::size_t csr_write{0};
    std::size_t mul_active_cycle{0};
    std::size_t div_active_cycle{0};
    std::size_t ptw_walk{0};
    std::size_t ptw_walk_cycle{0};
    std::size_t page_fault{0};
    std::size_t ras_miss{0};
    std::size_t redirect_branch{0};
    std::size_t redirect_fence_i{0};
    std::size_t redirect_trap{0};
    std::size_t redirect_mret{0};
    std::size_t fpu_stall_cycle{0};
    std::size_t illegal_inst{0};
    std::size_t axi_stall_cycle{0};
    std::size_t axi_error{0};
    std::size_t fpu_divsqrt{0};
    std::size_t fpu_fma{0};
    std::size_t fpu_exception{0};
    std::size_t csr_illegal{0};
    std::size_t plic_claim{0};
    std::size_t plic_complete{0};
    std::size_t flush_cycle{0};
    std::size_t uncached_access{0};
    std::size_t wbuf_occupancy_cycle{0};
    std::size_t tlb_superpage_hit{0};
    std::size_t tlb_asid_hit{0};
    std::size_t icache_line_fill{0};
    std::size_t dcache_line_fill{0};
    std::size_t dcache_writeback{0};
    std::size_t interrupt_pending_cycle{0};
    std::size_t interrupt_latency_cycle{0};
    std::size_t interrupt_latency_max{0};
    std::size_t interrupt_latency_count{0};
    std::size_t wfi_inst{0};
    std::size_t issue_occupancy_cycle{0};
    std::size_t retire_occupancy_cycle{0};
    std::size_t bubble_cycle{0};
    std::size_t div_iterations{0};
    std::size_t div_early_out{0};
};

// branch_operation 的统一语义是“条件分支 + JAL + JALR 总数”。单独的
// jal_operation/jalr_operation 是它的子集。保留饱和减法，使读取旧记录或
// 手工构造统计值时也不会因无符号下溢打印一个接近 SIZE_MAX 的数字。
export constexpr std::size_t ConditionalBranchCount(const DicArchCounterStats &stats) noexcept
{
    const auto jumps{stats.jal_operation + stats.jalr_operation};
    return stats.branch_operation >= jumps ? stats.branch_operation - jumps : 0;
}

export constexpr std::size_t ClassifiedInstructionCount(const DicArchCounterStats &stats) noexcept
{
    return stats.arithmetic_operation + stats.mdu_complete +
           stats.memory_access_operation + stats.control_status_register_operation +
           stats.branch_operation;
}

// 与 RTL 的 PerfEventKind 编码保持一致。把事件归类集中在可测试函数中，
// 避免 JAL/JALR 只增加子计数却漏掉 branch/jump 总数。enabled 位掩码让
// 单计数器开关关闭的类型不再参与分类统计（默认全开保持原语义）。
export inline constexpr unsigned PerfEventALU = 1U << 0;
export inline constexpr unsigned PerfEventMEM = 1U << 1;
export inline constexpr unsigned PerfEventCSR = 1U << 2;
export inline constexpr unsigned PerfEventBRANCH = 1U << 3;
export inline constexpr unsigned PerfEventJAL = 1U << 4;
export inline constexpr unsigned PerfEventJALR = 1U << 5;
export inline constexpr unsigned PerfEventAll =
    PerfEventALU | PerfEventMEM | PerfEventCSR | PerfEventBRANCH | PerfEventJAL |
    PerfEventJALR;
export constexpr void RecordExecutionEvent(
    DicArchCounterStats &stats, unsigned event_kind,
    unsigned enabled = PerfEventAll) noexcept
{
    switch (event_kind)
    {
    case 1:
        if (enabled & PerfEventALU)
            ++stats.arithmetic_operation;
        break;
    case 2:
        if (enabled & PerfEventMEM)
            ++stats.memory_access_operation;
        break;
    case 3:
        if (enabled & PerfEventCSR)
            ++stats.control_status_register_operation;
        break;
    case 4:
        if (enabled & PerfEventBRANCH)
            ++stats.branch_operation;
        break;
    case 5:
        if (enabled & PerfEventBRANCH)
            ++stats.branch_operation;
        if (enabled & PerfEventJAL)
            ++stats.jal_operation;
        break;
    case 6:
        if (enabled & PerfEventBRANCH)
            ++stats.branch_operation;
        if (enabled & PerfEventJALR)
            ++stats.jalr_operation;
        break;
    default: break;
    }
}

export struct CachePerformanceEstimate
{
    std::size_t access_count{0};
    double hit_rate{0.0};
    double miss_service_cycle{0.0};
    double amat{0.0};
};

// service_cycle 是只属于 miss/refill 的额外服务周期，而不是所有访问的
// LSU/IFU 占用周期。无访问时 AMAT 没有定义，记为 0；只要存在访问，即使
// miss 为 0，AMAT 也应等于 hit latency，而不是错误地显示为 0。
export constexpr CachePerformanceEstimate EstimateCachePerformance(
    std::size_t hit,
    std::size_t miss,
    std::size_t service_cycle,
    double hit_latency = 1.0) noexcept
{
    const auto access_count{hit + miss};
    if (access_count == 0)
        return {};

    const auto hit_rate{static_cast<double>(hit) /
                        static_cast<double>(access_count)};
    const auto miss_service_cycle{
        miss > 0 ? static_cast<double>(service_cycle) / static_cast<double>(miss)
                 : 0.0};
    const auto miss_rate{static_cast<double>(miss) /
                         static_cast<double>(access_count)};
    return {
        access_count,
        hit_rate,
        miss_service_cycle,
        hit_latency + miss_rate * miss_service_cycle};
}
