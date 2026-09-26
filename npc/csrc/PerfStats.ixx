export module npc.PerfStats;
import std;

export struct PerfStats
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
};

// branch_operation 的统一语义是“条件分支 + JAL + JALR 总数”。单独的
// jal_operation/jalr_operation 是它的子集。保留饱和减法，使读取旧记录或
// 手工构造统计值时也不会因无符号下溢打印一个接近 SIZE_MAX 的数字。
export constexpr std::size_t ConditionalBranchCount(const PerfStats &stats) noexcept
{
    const auto jumps{stats.jal_operation + stats.jalr_operation};
    return stats.branch_operation >= jumps ? stats.branch_operation - jumps : 0;
}

export constexpr std::size_t ClassifiedInstructionCount(const PerfStats &stats) noexcept
{
    return stats.arithmetic_operation + stats.mdu_complete +
           stats.memory_access_operation + stats.control_status_register_operation +
           stats.branch_operation;
}

// 与 RTL 的 PerfEventKind 编码保持一致。把事件归类集中在可测试函数中，
// 避免 JAL/JALR 只增加子计数却漏掉 branch/jump 总数。
export constexpr void RecordExecutionEvent(PerfStats &stats, unsigned event_kind) noexcept
{
    switch (event_kind)
    {
    case 1: ++stats.arithmetic_operation; break;
    case 2: ++stats.memory_access_operation; break;
    case 3: ++stats.control_status_register_operation; break;
    case 4: ++stats.branch_operation; break;
    case 5:
        ++stats.branch_operation;
        ++stats.jal_operation;
        break;
    case 6:
        ++stats.branch_operation;
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
