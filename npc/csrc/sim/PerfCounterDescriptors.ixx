module;
export module npc.PerfCounterDescriptors;
import std;
import npc.config;
import npc.DicArchCounterStats;

namespace npc
{
// pre-edge 字段须在上升沿前采样，其余沿后读。
export struct PerfSignals
{
    // pre-edge
    bool ifu_fetch{};
    bool exu_done{};
    bool lsu_load{};
    bool lsu_store{};
    bool mdu_req{};
    bool mdu_done{};
    unsigned mdu_op{};
    unsigned exu_event_kind{};
    bool ifu_stall_redirect{};
    // post-edge
    bool mdu_active{};
    bool mdu_wait{};
    bool ifu_stall_pipeline{};
    bool ifu_stall_axi{};
    bool ifu_stall_ar{};
    bool ifu_stall_r{};
    bool ifu_stall_idle{};
    bool execution_active{};
    bool exu_stall_lsu{};
    bool mem_waitslot{};
    bool alu_op{};
    bool mem_op{};
    bool csr_op{};
    bool branch_op{};
    bool lsu_active{};
    bool lsu_load_active{};
    bool lsu_store_active{};
    bool lsu_stall_read_ar{};
    bool lsu_stall_read_r{};
    bool lsu_stall_write_req{};
    bool lsu_stall_write_b{};
    bool icache_hit{};
    bool icache_miss{};
    bool dcache_hit{};
    bool dcache_miss{};
    bool dcache_refill_req{};
    bool dcache_refill_resp{};
    bool idu_stall_raw{};
    bool idu_stall_raw_loaduse{};
    bool idu_stall_raw_alu{};
    bool exu_idle_noinput{};
    bool trap_valid{};
    bool btb_hit{};
    bool btb_miss{};
    bool jal_btb_hit{};
    bool ras_hit{};
    bool branch_mispredict{};
    bool itlb_hit{};
    bool itlb_miss{};
    bool dtlb_hit{};
    bool dtlb_miss{};
    bool tlb_refill{};
    bool fpu_req{};
    bool fpu_done{};
    bool axi_ar{};
    bool axi_aw{};
    bool axi_r{};
    bool axi_w{};
    bool axi_b{};
    bool load_op{};
    bool store_op{};
    bool amo_op{};
    bool branch_taken{};
    bool branch_not_taken{};
    bool tlb_flush{};
    bool fpu_active{};
    bool fence_op{};
    bool rvc_inst{};
    bool system_op{};
    bool icache_refill{};
    bool dcache_refill{};
    bool timer_irq{};
    bool external_irq{};
    bool interrupt_taken{};
    bool wbuf_full{};
    bool wbuf_drain{};
    bool csr_read{};
    bool csr_write{};
    bool mul_active{};
    bool div_active{};
    bool ptw_walk{};
    bool ptw_walk_cycle{};
    bool page_fault{};
    bool ras_miss{};
    bool redirect_branch{};
    bool redirect_fence_i{};
    bool redirect_trap{};
    bool redirect_mret{};
    bool fpu_stall{};
    bool illegal_inst{};
    bool axi_stall{};
    bool axi_error{};
    bool fpu_divsqrt{};
    bool fpu_fma{};
    bool fpu_exception{};
    bool csr_illegal{};
    bool plic_claim{};
    bool plic_complete{};
    bool flush_cycle{};
    bool uncached_access{};
    unsigned wbuf_count{};
    bool tlb_superpage_hit{};
    bool tlb_asid_hit{};
    bool icache_line_fill{};
    bool dcache_line_fill{};
    bool dcache_writeback{};
    bool irq_pending{};
    bool wfi_inst{};
    bool issue_occupancy{};
    bool retire_occupancy{};
    bool bubble_cycle{};
    bool div_done{};
    unsigned div_iterations{};
    bool div_early_out{};
};

namespace detail
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
}
// descriptor 表直接引用这些采样函数；它们不导出，只作为模块内实现细节。
using detail::AluActive;
using detail::Bit;
using detail::BranchActive;
using detail::Count;
using detail::CsrActive;
using detail::DivEarlyOut;
using detail::DivIterations;
using detail::MduDiv;
using detail::MduDivu;
using detail::MduMul;
using detail::MduMulh;
using detail::MduMulhsu;
using detail::MduMulhu;
using detail::MduRem;
using detail::MduRemu;
using detail::MemActive;
// 采样方式决定 PerfCounterAccumulator::Sample 如何处理该字段：
// Signal 直接累加 sample 的返回值；EventClassified 由
// RecordExecutionEvent 按 event_bit 归入 EXU 操作类别；InterruptLatency
// 由 pending→trap 跟踪器在每拍结束时写回。
export enum class CounterSampleKind : std::uint8_t
{
    Signal,
    EventClassified,
    InterruptLatency,
};

export enum class CounterCsvFormat : std::uint8_t
{
    Integer,
    Fixed2,
};

// 计数器是否进入文本/CSV。InternalOnly 是已采样但基线从未输出、且本阶段
// 不允许新增列的计数器；新增计数器必须显式选择 TextAndCsv/TextOnly，
// 否则 perf-stats-test 的一致性检查会失败。
export enum class CounterReporting : std::uint8_t
{
    TextAndCsv,
    TextOnly,
    InternalOnly,
};

// 文本输出的分组；None 表示该计数器只参与派生行，不单独成行。
export enum class PrintGroup : std::uint8_t
{
    None,
    ReportTitle,
    Overview,
    Ratio,
    Ifu,
    Exu,
    Idu,
    Trap,
    BranchPrediction,
    MmuTlb,
    Fpu,
    Axi,
    InstructionMix,
    CacheDetail,
    Interrupt,
    WriteBuffer,
    CsrMdu,
    QueueBubble,
    LsuFsm,
    LsuState,
    ICache,
    DCache,
};

export constexpr std::string_view PrintGroupTitle(PrintGroup group) noexcept
{
    switch (group)
    {
    case PrintGroup::ReportTitle:
        return "数字集成电路计算机体系结构性能计数器";
    case PrintGroup::Ratio:
        return "指令类别占比(分母为已分类EXU操作，不是推测取指数):";
    case PrintGroup::Ifu:
        return "IFU状态观测(各项可重叠，不可相加):";
    case PrintGroup::Exu:
        return "EXU流水状态(各项可重叠):";
    case PrintGroup::Idu:
        return "IDU数据冒险阻塞分析:";
    case PrintGroup::BranchPrediction:
        return "分支预测:";
    case PrintGroup::MmuTlb:
        return "MMU/TLB:";
    case PrintGroup::Fpu:
        return "FPU:";
    case PrintGroup::Axi:
        return "AXI:";
    case PrintGroup::InstructionMix:
        return "指令细分:";
    case PrintGroup::CacheDetail:
        return "缓存细节:";
    case PrintGroup::Interrupt:
        return "中断:";
    case PrintGroup::WriteBuffer:
        return "写缓冲:";
    case PrintGroup::QueueBubble:
        return "队列/气泡:";
    case PrintGroup::LsuFsm:
        return "LSU内存事务FSM占用(请求/响应/写缓冲等待，非端到端指令延迟):";
    case PrintGroup::LsuState:
        return "LSU状态占用分解(非纯AXI stall，B等待可后台重叠):";
    case PrintGroup::ICache:
        return "ICache 性能:";
    case PrintGroup::DCache:
        return "DCache 性能:";
    case PrintGroup::None:
    case PrintGroup::Overview:
    case PrintGroup::Trap:
    case PrintGroup::CsrMdu:
        return {};
    }
    return {};
}

// 单一事实表：字段、Kconfig 开关、采样函数、文本标签、CSV 标签/格式
// 都只在这里维护。PerfCounterAccumulator、NPCTrap 文本和 NPCSimResult
// CSV 都消费这张表；新增计数器时漏掉任一路由 perf-stats-test 的一致性
// 检查发现。
export struct CounterDescriptor
{
    bool enabled;
    CounterSampleKind sample_kind;
    std::size_t DicArchCounterStats::*field;
    std::size_t (*sample)(const PerfSignals &);
    unsigned event_bit;
    CounterReporting reporting;
    std::string_view name;
    PrintGroup print_group;
    std::string_view print_label;
    std::string_view csv_label;
    CounterCsvFormat csv_format;
};

#define NPC_COUNTER(field_, enabled_, sample_, group_, print_label_, csv_label_) \
    {.enabled = (enabled_),                                                    \
     .sample_kind = CounterSampleKind::Signal,                                 \
     .field = &DicArchCounterStats::field_,                                     \
     .sample = (sample_),                                                      \
     .event_bit = 0U,                                                          \
     .reporting = CounterReporting::TextAndCsv,                                \
     .name = #field_,                                                           \
     .print_group = (group_),                                                  \
     .print_label = (print_label_),                                            \
     .csv_label = (csv_label_),                                                \
     .csv_format = CounterCsvFormat::Integer}
#define NPC_EVENT_COUNTER(field_, enabled_, event_bit_, group_, print_label_, \
                            csv_label_)                                          \
    {.enabled = (enabled_),                                                    \
     .sample_kind = CounterSampleKind::EventClassified,                        \
     .field = &DicArchCounterStats::field_,                                     \
     .sample = nullptr,                                                        \
     .event_bit = (event_bit_),                                                \
     .reporting = CounterReporting::TextAndCsv,                                \
     .name = #field_,                                                           \
     .print_group = (group_),                                                  \
     .print_label = (print_label_),                                            \
     .csv_label = (csv_label_),                                                \
     .csv_format = CounterCsvFormat::Integer}
#define NPC_LATENCY_COUNTER(field_, enabled_, group_, print_label_, csv_label_) \
    {.enabled = (enabled_),                                                    \
     .sample_kind = CounterSampleKind::InterruptLatency,                       \
     .field = &DicArchCounterStats::field_,                                     \
     .sample = nullptr,                                                        \
     .event_bit = 0U,                                                          \
     .reporting = CounterReporting::TextAndCsv,                                \
     .name = #field_,                                                           \
     .print_group = (group_),                                                  \
     .print_label = (print_label_),                                            \
     .csv_label = (csv_label_),                                                \
     .csv_format = CounterCsvFormat::Integer}
#define NPC_TEXT_COUNTER(field_, enabled_, sample_, group_, print_label_,     \
                           csv_label_)                                           \
    {.enabled = (enabled_),                                                    \
     .sample_kind = CounterSampleKind::Signal,                                 \
     .field = &DicArchCounterStats::field_,                                     \
     .sample = (sample_),                                                      \
     .event_bit = 0U,                                                          \
     .reporting = CounterReporting::TextOnly,                                  \
     .name = #field_,                                                           \
     .print_group = (group_),                                                  \
     .print_label = (print_label_),                                            \
     .csv_label = (csv_label_),                                                \
     .csv_format = CounterCsvFormat::Integer}
#define NPC_INTERNAL_COUNTER(field_, enabled_, sample_, group_, print_label_, \
                                 csv_label_)                                       \
    {.enabled = (enabled_),                                                    \
     .sample_kind = CounterSampleKind::Signal,                                 \
     .field = &DicArchCounterStats::field_,                                     \
     .sample = (sample_),                                                      \
     .event_bit = 0U,                                                          \
     .reporting = CounterReporting::InternalOnly,                              \
     .name = #field_,                                                           \
     .print_group = (group_),                                                  \
     .print_label = (print_label_),                                            \
     .csv_label = (csv_label_),                                                \
     .csv_format = CounterCsvFormat::Integer}

export inline constexpr CounterDescriptor CounterDescriptors[]{
    NPC_COUNTER(instruction_fetch, PerfDicArchCounterIfuFetch, &Bit<&PerfSignals::ifu_fetch>, PrintGroup::Overview, "IFU交付指令响应(含后续可能冲刷的指令)", "IFU交付响应"),
    NPC_COUNTER(execution_complete, PerfDicArchCounterExuDone, &Bit<&PerfSignals::exu_done>, PrintGroup::Overview, "EXU输出握手", "EXU输出握手"),
    NPC_COUNTER(load_data, PerfDicArchCounterLsuLoad, &Bit<&PerfSignals::lsu_load>, PrintGroup::Overview, "LSU完成读操作(含故障完成)", "LSU读完成"),
    NPC_COUNTER(store_data, PerfDicArchCounterLsuStore, &Bit<&PerfSignals::lsu_store>, PrintGroup::Overview, "LSU完成写操作(含故障完成)", "LSU写完成"),
    NPC_EVENT_COUNTER(arithmetic_operation, PerfDicArchCounterAluOp, PerfEventALU, PrintGroup::Overview, "ALU指令", "ALU指令"),
    NPC_COUNTER(mdu_request, PerfDicArchCounterMduReq, &Bit<&PerfSignals::mdu_req>, PrintGroup::Overview, "MDU请求", "MDU请求"),
    NPC_COUNTER(mdu_complete, PerfDicArchCounterMduDone, &Bit<&PerfSignals::mdu_done>, PrintGroup::Overview, "MDU完成", "MDU完成"),
    NPC_COUNTER(mdu_mul, PerfDicArchCounterMduDone && PerfDicArchCounterMduOp, &MduMul, PrintGroup::Overview, "  MUL", "MUL"),
    NPC_COUNTER(mdu_mulh, PerfDicArchCounterMduDone && PerfDicArchCounterMduOp, &MduMulh, PrintGroup::Overview, "  MULH", "MULH"),
    NPC_COUNTER(mdu_mulhsu, PerfDicArchCounterMduDone && PerfDicArchCounterMduOp, &MduMulhsu, PrintGroup::Overview, "  MULHSU", "MULHSU"),
    NPC_COUNTER(mdu_mulhu, PerfDicArchCounterMduDone && PerfDicArchCounterMduOp, &MduMulhu, PrintGroup::Overview, "  MULHU", "MULHU"),
    NPC_COUNTER(mdu_div, PerfDicArchCounterMduDone && PerfDicArchCounterMduOp, &MduDiv, PrintGroup::Overview, "  DIV", "DIV"),
    NPC_COUNTER(mdu_divu, PerfDicArchCounterMduDone && PerfDicArchCounterMduOp, &MduDivu, PrintGroup::Overview, "  DIVU", "DIVU"),
    NPC_COUNTER(mdu_rem, PerfDicArchCounterMduDone && PerfDicArchCounterMduOp, &MduRem, PrintGroup::Overview, "  REM", "REM"),
    NPC_COUNTER(mdu_remu, PerfDicArchCounterMduDone && PerfDicArchCounterMduOp, &MduRemu, PrintGroup::Overview, "  REMU", "REMU"),
    NPC_COUNTER(mdu_active_cycle, PerfDicArchCounterMduActive, &Bit<&PerfSignals::mdu_active>, PrintGroup::Overview, "MDU发射或在途活动", "MDU发射或在途活动周期"),
    NPC_COUNTER(mdu_wait_cycle, PerfDicArchCounterMduWait, &Bit<&PerfSignals::mdu_wait>, PrintGroup::Overview, "MDU等待计算结果", "MDU等待计算结果周期"),
    NPC_EVENT_COUNTER(memory_access_operation, PerfDicArchCounterMemOp, PerfEventMEM, PrintGroup::Overview, "访存指令", "访存指令"),
    NPC_EVENT_COUNTER(control_status_register_operation, PerfDicArchCounterCsrOp, PerfEventCSR, PrintGroup::Overview, "CSR指令", "CSR指令"),
    NPC_EVENT_COUNTER(branch_operation, PerfDicArchCounterBranchOp, PerfEventBRANCH, PrintGroup::Overview, "分支/跳转指令", "分支跳转总数"),
    NPC_EVENT_COUNTER(jal_operation, PerfDicArchCounterJalOp, PerfEventJAL, PrintGroup::Overview, "  其中 jal指令", "JAL"),
    NPC_EVENT_COUNTER(jalr_operation, PerfDicArchCounterJalrOp, PerfEventJALR, PrintGroup::Overview, "  其中 jalr指令", "JALR"),
    NPC_COUNTER(instruction_fetch_stall_pipeline, PerfDicArchCounterIfuStallPipeline, &Bit<&PerfSignals::ifu_stall_pipeline>, PrintGroup::Ifu, "响应有效但下游未接受", "IFU响应反压"),
    NPC_COUNTER(instruction_fetch_stall_axi, PerfDicArchCounterIfuStallAxi, &Bit<&PerfSignals::ifu_stall_axi>, PrintGroup::Ifu, "ICache refill AR/R状态合计", "ICache_refill_AR_R状态合计"),
    NPC_COUNTER(instruction_fetch_stall_ar, PerfDicArchCounterIfuStallAr, &Bit<&PerfSignals::ifu_stall_ar>, PrintGroup::Ifu, "  ICache refill处于AR请求状态(含握手拍)", "ICache_refill_AR状态"),
    NPC_COUNTER(instruction_fetch_stall_r, PerfDicArchCounterIfuStallR, &Bit<&PerfSignals::ifu_stall_r>, PrintGroup::Ifu, "  ICache refill处于R响应状态(含传输拍)", "ICache_refill_R状态"),
    NPC_COUNTER(instruction_fetch_stall_redirect, PerfDicArchCounterIfuStallRedirect, &Bit<&PerfSignals::ifu_stall_redirect>, PrintGroup::Ifu, "控制流重定向事件", "控制流重定向事件"),
    NPC_COUNTER(instruction_fetch_stall_idle, PerfDicArchCounterIfuStallIdle, &Bit<&PerfSignals::ifu_stall_idle>, PrintGroup::Ifu, "IFU空闲(无取指请求)", "IFU空闲"),
    NPC_INTERNAL_COUNTER(execution_active_cycle, PerfDicArchCounterExecutionActive, &Bit<&PerfSignals::execution_active>, PrintGroup::None, "", ""),
    NPC_COUNTER(exu_stall_lsu, PerfDicArchCounterExuStallLsu, &Bit<&PerfSignals::exu_stall_lsu>, PrintGroup::Exu, "EXU下游/顺序等待(已排除MDU计算等待)", "EXU下游或顺序等待(已排除MDU计算)"),
    NPC_INTERNAL_COUNTER(arithmetic_operation_active_cycle, PerfDicArchCounterAluOp, &AluActive, PrintGroup::None, "", ""),
    NPC_TEXT_COUNTER(memory_access_operation_active_cycle, PerfDicArchCounterMemOp, &MemActive, PrintGroup::Ratio, "访存类指令在EXU输入端驻留摊销", ""),
    NPC_INTERNAL_COUNTER(control_status_register_operation_active_cycle, PerfDicArchCounterCsrOp, &CsrActive, PrintGroup::None, "", ""),
    NPC_INTERNAL_COUNTER(branch_operation_active_cycle, PerfDicArchCounterBranchOp, &BranchActive, PrintGroup::None, "", ""),
    NPC_TEXT_COUNTER(load_store_unit_active_cycle, PerfDicArchCounterLsuActive, &Bit<&PerfSignals::lsu_active>, PrintGroup::LsuFsm, "平均FSM占用", ""),
    NPC_TEXT_COUNTER(load_store_unit_load_active_cycle, PerfDicArchCounterLsuLoadActive, &Bit<&PerfSignals::lsu_load_active>, PrintGroup::LsuFsm, "  读FSM占用", ""),
    NPC_TEXT_COUNTER(load_store_unit_store_active_cycle, PerfDicArchCounterLsuStoreActive, &Bit<&PerfSignals::lsu_store_active>, PrintGroup::LsuFsm, "  写FSM占用", ""),
    NPC_COUNTER(lsu_stall_read_ar, PerfDicArchCounterLsuStallReadAr, &Bit<&PerfSignals::lsu_stall_read_ar>, PrintGroup::LsuState, "  LSU读请求状态(含DCache hit内部握手)", "LSU读请求状态"),
    NPC_COUNTER(lsu_stall_read_r, PerfDicArchCounterLsuStallReadR, &Bit<&PerfSignals::lsu_stall_read_r>, PrintGroup::LsuState, "  LSU读响应状态(含DCache内部等待)", "LSU读响应状态"),
    NPC_COUNTER(lsu_stall_write_req, PerfDicArchCounterLsuStallWriteReq, &Bit<&PerfSignals::lsu_stall_write_req>, PrintGroup::LsuState, "  写缓冲无空位", "写缓冲无空位"),
    NPC_COUNTER(lsu_stall_write_b, PerfDicArchCounterLsuStallWriteB, &Bit<&PerfSignals::lsu_stall_write_b>, PrintGroup::LsuState, "  写响应在途(可后台重叠)", "写响应在途"),
    NPC_COUNTER(icache_hit, PerfDicArchCounterIcacheHit, &Bit<&PerfSignals::icache_hit>, PrintGroup::ICache, "  命中", "ICache命中"),
    NPC_COUNTER(icache_miss, PerfDicArchCounterIcacheMiss, &Bit<&PerfSignals::icache_miss>, PrintGroup::ICache, "  缺失", "ICache缺失"),
    NPC_COUNTER(dcache_hit, PerfDicArchCounterDcacheHit, &Bit<&PerfSignals::dcache_hit>, PrintGroup::DCache, "  load命中", "DCache_load命中"),
    NPC_COUNTER(dcache_miss, PerfDicArchCounterDcacheMiss, &Bit<&PerfSignals::dcache_miss>, PrintGroup::DCache, "  可缓存load缺失", "DCache_可缓存load缺失"),
    NPC_COUNTER(dcache_refill_req_cycle, PerfDicArchCounterDcacheRefillReq, &Bit<&PerfSignals::dcache_refill_req>, PrintGroup::DCache, "  refill AR状态", "DCache_refill_AR周期"),
    NPC_COUNTER(dcache_refill_resp_cycle, PerfDicArchCounterDcacheRefillResp, &Bit<&PerfSignals::dcache_refill_resp>, PrintGroup::DCache, "  refill R状态", "DCache_refill_R周期"),
    NPC_COUNTER(idu_stall_raw, PerfDicArchCounterIduStallRaw, &Bit<&PerfSignals::idu_stall_raw>, PrintGroup::Idu, "RAW阻塞合计", "IDU_RAW阻塞"),
    NPC_COUNTER(idu_stall_raw_loaduse, PerfDicArchCounterIduStallRawLoaduse, &Bit<&PerfSignals::idu_stall_raw_loaduse>, PrintGroup::Idu, "  load-use(等LSU数据,转发无法消除)", "IDU_RAW_loaduse"),
    NPC_COUNTER(idu_stall_raw_alu, PerfDicArchCounterIduStallRawAlu, &Bit<&PerfSignals::idu_stall_raw_alu>, PrintGroup::Idu, "  非load的未就绪依赖(主要含MDU/隐藏写者)", "IDU_RAW_非load未就绪"),
    NPC_COUNTER(exu_idle_noinput, PerfDicArchCounterExuIdleNoinput, &Bit<&PerfSignals::exu_idle_noinput>, PrintGroup::Exu, "EXU空转无输入(上游供给不足)", "EXU空转等输入"),
    NPC_COUNTER(trap_count, PerfDicArchCounterTrapCount, &Bit<&PerfSignals::trap_valid>, PrintGroup::Trap, "异常/中断提交", "异常提交"),
    NPC_COUNTER(mem_waitslot, PerfDicArchCounterMemWaitslot, &Bit<&PerfSignals::mem_waitslot>, PrintGroup::Exu, "EX/MEM等待槽占用(5级拆分买到的访存重叠)", "EX/MEM等待槽占用"),
    NPC_COUNTER(btb_hit, PerfDicArchCounterBtbHit, &Bit<&PerfSignals::btb_hit>, PrintGroup::BranchPrediction, "BTB命中", "BTB命中"),
    NPC_COUNTER(btb_miss, PerfDicArchCounterBtbMiss, &Bit<&PerfSignals::btb_miss>, PrintGroup::BranchPrediction, "BTB缺失", "BTB缺失"),
    NPC_COUNTER(jal_btb_hit, PerfDicArchCounterJalBtbHit, &Bit<&PerfSignals::jal_btb_hit>, PrintGroup::BranchPrediction, "  JAL-BTB命中", "JAL_BTB命中"),
    NPC_COUNTER(ras_hit, PerfDicArchCounterRasHit, &Bit<&PerfSignals::ras_hit>, PrintGroup::BranchPrediction, "  RAS命中", "RAS命中"),
    NPC_COUNTER(branch_mispredict, PerfDicArchCounterBranchMispredict, &Bit<&PerfSignals::branch_mispredict>, PrintGroup::BranchPrediction, "  分支/跳转误预测", "分支误预测"),
    NPC_COUNTER(itlb_hit, PerfDicArchCounterItlbHit, &Bit<&PerfSignals::itlb_hit>, PrintGroup::MmuTlb, "ITLB命中", "ITLB命中"),
    NPC_COUNTER(itlb_miss, PerfDicArchCounterItlbMiss, &Bit<&PerfSignals::itlb_miss>, PrintGroup::MmuTlb, "ITLB缺失", "ITLB缺失"),
    NPC_COUNTER(dtlb_hit, PerfDicArchCounterDtlbHit, &Bit<&PerfSignals::dtlb_hit>, PrintGroup::MmuTlb, "DTLB命中", "DTLB命中"),
    NPC_COUNTER(dtlb_miss, PerfDicArchCounterDtlbMiss, &Bit<&PerfSignals::dtlb_miss>, PrintGroup::MmuTlb, "DTLB缺失", "DTLB缺失"),
    NPC_COUNTER(tlb_refill, PerfDicArchCounterTlbRefill, &Bit<&PerfSignals::tlb_refill>, PrintGroup::MmuTlb, "TLB回填", "TLB回填"),
    NPC_COUNTER(fpu_req, PerfDicArchCounterFpuReq, &Bit<&PerfSignals::fpu_req>, PrintGroup::Fpu, "请求", "FPU请求"),
    NPC_COUNTER(fpu_done, PerfDicArchCounterFpuDone, &Bit<&PerfSignals::fpu_done>, PrintGroup::Fpu, "完成", "FPU完成"),
    NPC_COUNTER(axi_ar, PerfDicArchCounterAxiAr, &Bit<&PerfSignals::axi_ar>, PrintGroup::Axi, "AR", "AXI_AR"),
    NPC_COUNTER(axi_aw, PerfDicArchCounterAxiAw, &Bit<&PerfSignals::axi_aw>, PrintGroup::Axi, "AW", "AXI_AW"),
    NPC_COUNTER(axi_r, PerfDicArchCounterAxiR, &Bit<&PerfSignals::axi_r>, PrintGroup::Axi, "R", "AXI_R"),
    NPC_COUNTER(axi_w, PerfDicArchCounterAxiW, &Bit<&PerfSignals::axi_w>, PrintGroup::Axi, "W", "AXI_W"),
    NPC_COUNTER(axi_b, PerfDicArchCounterAxiB, &Bit<&PerfSignals::axi_b>, PrintGroup::Axi, "B", "AXI_B"),
    NPC_COUNTER(load_op, PerfDicArchCounterLoadOp, &Bit<&PerfSignals::load_op>, PrintGroup::InstructionMix, "load", "load指令"),
    NPC_COUNTER(store_op, PerfDicArchCounterStoreOp, &Bit<&PerfSignals::store_op>, PrintGroup::InstructionMix, "store", "store指令"),
    NPC_COUNTER(amo_op, PerfDicArchCounterAmoOp, &Bit<&PerfSignals::amo_op>, PrintGroup::InstructionMix, "AMO", "AMO指令"),
    NPC_COUNTER(branch_taken, PerfDicArchCounterBranchTaken, &Bit<&PerfSignals::branch_taken>, PrintGroup::BranchPrediction, "taken", "分支taken"),
    NPC_COUNTER(branch_not_taken, PerfDicArchCounterBranchNotTaken, &Bit<&PerfSignals::branch_not_taken>, PrintGroup::BranchPrediction, "not-taken", "分支not-taken"),
    NPC_COUNTER(tlb_flush, PerfDicArchCounterTlbFlush, &Bit<&PerfSignals::tlb_flush>, PrintGroup::MmuTlb, "TLB冲刷", "TLB冲刷"),
    NPC_COUNTER(fpu_active_cycle, PerfDicArchCounterFpuActive, &Bit<&PerfSignals::fpu_active>, PrintGroup::Fpu, "忙碌", "FPU忙碌周期"),
    NPC_COUNTER(fence_op, PerfDicArchCounterFenceOp, &Bit<&PerfSignals::fence_op>, PrintGroup::InstructionMix, "fence", "fence指令"),
    NPC_COUNTER(rvc_inst, PerfDicArchCounterRvcInst, &Bit<&PerfSignals::rvc_inst>, PrintGroup::InstructionMix, "RVC", "RVC指令"),
    NPC_COUNTER(system_op, PerfDicArchCounterSystemOp, &Bit<&PerfSignals::system_op>, PrintGroup::InstructionMix, "system", "system指令"),
    NPC_COUNTER(icache_refill, PerfDicArchCounterIcacheRefill, &Bit<&PerfSignals::icache_refill>, PrintGroup::CacheDetail, "ICache", "ICache回填"),
    NPC_COUNTER(dcache_refill, PerfDicArchCounterDcacheRefill, &Bit<&PerfSignals::dcache_refill>, PrintGroup::CacheDetail, "DCache", "DCache回填"),
    NPC_COUNTER(timer_irq, PerfDicArchCounterTimerIrq, &Bit<&PerfSignals::timer_irq>, PrintGroup::Interrupt, "timer", "定时器中断"),
    NPC_COUNTER(external_irq, PerfDicArchCounterExternalIrq, &Bit<&PerfSignals::external_irq>, PrintGroup::Interrupt, "external", "外部中断"),
    NPC_COUNTER(interrupt_taken, PerfDicArchCounterInterruptTaken, &Bit<&PerfSignals::interrupt_taken>, PrintGroup::Interrupt, "  实际进入中断", "进入中断"),
    NPC_COUNTER(wbuf_full, PerfDicArchCounterWbufFull, &Bit<&PerfSignals::wbuf_full>, PrintGroup::WriteBuffer, "无空位", "写缓冲无空位"),
    NPC_COUNTER(wbuf_drain, PerfDicArchCounterWbufDrain, &Bit<&PerfSignals::wbuf_drain>, PrintGroup::WriteBuffer, "排空", "写缓冲排空"),
    NPC_COUNTER(csr_read, PerfDicArchCounterCsrRead, &Bit<&PerfSignals::csr_read>, PrintGroup::CsrMdu, "CSR", "CSR读"),
    NPC_COUNTER(csr_write, PerfDicArchCounterCsrWrite, &Bit<&PerfSignals::csr_write>, PrintGroup::CsrMdu, "CSR写", "CSR写"),
    NPC_COUNTER(mul_active_cycle, PerfDicArchCounterMulActive, &Bit<&PerfSignals::mul_active>, PrintGroup::CsrMdu, "MDU 活跃周期", "乘法活跃周期"),
    NPC_COUNTER(div_active_cycle, PerfDicArchCounterDivActive, &Bit<&PerfSignals::div_active>, PrintGroup::CsrMdu, "除法活跃周期", "除法活跃周期"),
    NPC_COUNTER(ptw_walk, PerfDicArchCounterPtwWalk, &Bit<&PerfSignals::ptw_walk>, PrintGroup::MmuTlb, "PTW walk", "PTW walk"),
    NPC_COUNTER(ptw_walk_cycle, PerfDicArchCounterPtwWalkCycle, &Bit<&PerfSignals::ptw_walk_cycle>, PrintGroup::MmuTlb, "PTW活跃周期", "PTW活跃周期"),
    NPC_COUNTER(page_fault, PerfDicArchCounterPageFault, &Bit<&PerfSignals::page_fault>, PrintGroup::MmuTlb, "page fault", "page fault"),
    NPC_COUNTER(ras_miss, PerfDicArchCounterRasMiss, &Bit<&PerfSignals::ras_miss>, PrintGroup::BranchPrediction, "  RAS缺失", "RAS缺失"),
    NPC_COUNTER(redirect_branch, PerfDicArchCounterRedirectBranch, &Bit<&PerfSignals::redirect_branch>, PrintGroup::BranchPrediction, "重定向branch", "重定向branch"),
    NPC_COUNTER(redirect_fence_i, PerfDicArchCounterRedirectFenceI, &Bit<&PerfSignals::redirect_fence_i>, PrintGroup::BranchPrediction, "重定向fence.i", "重定向fence.i"),
    NPC_COUNTER(redirect_trap, PerfDicArchCounterRedirectTrap, &Bit<&PerfSignals::redirect_trap>, PrintGroup::BranchPrediction, "重定向trap", "重定向trap"),
    NPC_COUNTER(redirect_mret, PerfDicArchCounterRedirectMret, &Bit<&PerfSignals::redirect_mret>, PrintGroup::BranchPrediction, "重定向mret", "重定向mret"),
    NPC_COUNTER(fpu_stall_cycle, PerfDicArchCounterFpuStall, &Bit<&PerfSignals::fpu_stall>, PrintGroup::Fpu, "等待周期", "FPU等待周期"),
    NPC_COUNTER(illegal_inst, PerfDicArchCounterIllegalInst, &Bit<&PerfSignals::illegal_inst>, PrintGroup::InstructionMix, "  非法指令", "非法指令"),
    NPC_COUNTER(axi_stall_cycle, PerfDicArchCounterAxiStall, &Bit<&PerfSignals::axi_stall>, PrintGroup::Axi, "stall", "AXI_stall"),
    NPC_COUNTER(axi_error, PerfDicArchCounterAxiError, &Bit<&PerfSignals::axi_error>, PrintGroup::Axi, "error", "AXI_error"),
    NPC_COUNTER(fpu_divsqrt, PerfDicArchCounterFpuDivsqrt, &Bit<&PerfSignals::fpu_divsqrt>, PrintGroup::Fpu, "DIV/SQRT", "FPU_DIVSQRT"),
    NPC_COUNTER(fpu_fma, PerfDicArchCounterFpuFma, &Bit<&PerfSignals::fpu_fma>, PrintGroup::Fpu, "FMA", "FPU_FMA"),
    NPC_COUNTER(fpu_exception, PerfDicArchCounterFpuException, &Bit<&PerfSignals::fpu_exception>, PrintGroup::Fpu, "fflags异常", "FPU异常"),
    NPC_COUNTER(csr_illegal, PerfDicArchCounterCsrIllegal, &Bit<&PerfSignals::csr_illegal>, PrintGroup::CsrMdu, "CSR 非法", "CSR非法"),
    NPC_COUNTER(plic_claim, PerfDicArchCounterPlicClaim, &Bit<&PerfSignals::plic_claim>, PrintGroup::Interrupt, "PLIC claim", "PLIC_claim"),
    NPC_COUNTER(plic_complete, PerfDicArchCounterPlicComplete, &Bit<&PerfSignals::plic_complete>, PrintGroup::Interrupt, "PLIC_complete", "PLIC_complete"),
    NPC_COUNTER(flush_cycle, PerfDicArchCounterFlushCycle, &Bit<&PerfSignals::flush_cycle>, PrintGroup::InstructionMix, "  流水线冲刷周期", "冲刷周期"),
    NPC_COUNTER(uncached_access, PerfDicArchCounterUncachedAccess, &Bit<&PerfSignals::uncached_access>, PrintGroup::CacheDetail, "  非缓存/MMIO完成访问", "非缓存访问"),
    NPC_COUNTER(wbuf_occupancy_cycle, PerfDicArchCounterWbufOccupancy, &Count<&PerfSignals::wbuf_count>, PrintGroup::WriteBuffer, "  平均占用", "写缓冲占用周期和"),
    NPC_COUNTER(tlb_superpage_hit, PerfDicArchCounterTlbSuperpageHit, &Bit<&PerfSignals::tlb_superpage_hit>, PrintGroup::MmuTlb, "超级页", "TLB超级页命中"),
    NPC_COUNTER(tlb_asid_hit, PerfDicArchCounterTlbAsidHit, &Bit<&PerfSignals::tlb_asid_hit>, PrintGroup::MmuTlb, "ASID命中", "TLB_ASID命中"),
    NPC_COUNTER(icache_line_fill, PerfDicArchCounterIcacheLineFill, &Bit<&PerfSignals::icache_line_fill>, PrintGroup::CacheDetail, "ICache行填充", "ICache行填充"),
    NPC_COUNTER(dcache_line_fill, PerfDicArchCounterDcacheLineFill, &Bit<&PerfSignals::dcache_line_fill>, PrintGroup::CacheDetail, "DCache行填充", "DCache行填充"),
    NPC_COUNTER(dcache_writeback, PerfDicArchCounterDcacheWriteback, &Bit<&PerfSignals::dcache_writeback>, PrintGroup::CacheDetail, "  DCache写直达/store行更新", "DCache写直达更新"),
    NPC_COUNTER(interrupt_pending_cycle, PerfDicArchCounterInterruptPendingCycle, &Bit<&PerfSignals::irq_pending>, PrintGroup::Interrupt, "  pending周期", "中断pending周期"),
    NPC_LATENCY_COUNTER(interrupt_latency_cycle, PerfDicArchCounterInterruptLatency, PrintGroup::Interrupt, "平均/最大 pending→trap 延迟", "中断延迟周期和"),
    NPC_LATENCY_COUNTER(interrupt_latency_max, PerfDicArchCounterInterruptLatency, PrintGroup::Interrupt, "最大 pending→trap 延迟", "中断最大延迟"),
    NPC_LATENCY_COUNTER(interrupt_latency_count, PerfDicArchCounterInterruptLatency, PrintGroup::Interrupt, "pending→trap 延迟样本数", "中断延迟样本数"),
    NPC_COUNTER(wfi_inst, PerfDicArchCounterWfiInst, &Bit<&PerfSignals::wfi_inst>, PrintGroup::InstructionMix, "  WFI 指令(按NOP执行)", "WFI指令"),
    NPC_COUNTER(issue_occupancy_cycle, PerfDicArchCounterIssueOccupancy, &Bit<&PerfSignals::issue_occupancy>, PrintGroup::QueueBubble, "  EXU输入槽占用率", "发射槽占用周期"),
    NPC_COUNTER(retire_occupancy_cycle, PerfDicArchCounterRetireOccupancy, &Bit<&PerfSignals::retire_occupancy>, PrintGroup::QueueBubble, "  EXU输出/退休槽占用率", "退休槽占用周期"),
    NPC_COUNTER(bubble_cycle, PerfDicArchCounterBubbleCycle, &Bit<&PerfSignals::bubble_cycle>, PrintGroup::QueueBubble, "  EXU输入气泡占比", "EXU气泡周期"),
    NPC_COUNTER(div_iterations, PerfDicArchCounterDivIterations, &DivIterations, PrintGroup::CsrMdu, "除法实际迭代位数", "除法实际迭代位数"),
    NPC_COUNTER(div_early_out, PerfDicArchCounterDivEarlyOut, &DivEarlyOut, PrintGroup::CsrMdu, "除法提前结束", "除法提前结束"),
};

#undef NPC_INTERNAL_COUNTER
#undef NPC_TEXT_COUNTER
#undef NPC_LATENCY_COUNTER
#undef NPC_EVENT_COUNTER
#undef NPC_COUNTER

export inline constexpr std::size_t CounterDescriptorCount{
    std::size(CounterDescriptors)};

export constexpr std::size_t CounterDescriptorIndex(
    std::size_t DicArchCounterStats::*field) noexcept
{
    for (std::size_t index{0}; index < CounterDescriptorCount; ++index)
    {
        if (CounterDescriptors[index].field == field)
            return index;
    }
    return CounterDescriptorCount;
}

export constexpr const CounterDescriptor &CounterDescriptorFor(
    std::size_t DicArchCounterStats::*field) noexcept
{
    const auto index{CounterDescriptorIndex(field)};
    if (index == CounterDescriptorCount)
        std::unreachable();
    return CounterDescriptors[index];
}

// 指令类别占比使用与概览行不同的标签（例如 MDU 概览行叫“MDU完成”，
// 占比行叫“MDU指令占比”），仍由同一字段的 descriptor 派生。
export constexpr std::string_view CounterShareLabel(
    std::size_t DicArchCounterStats::*field) noexcept
{
    if (field == &DicArchCounterStats::arithmetic_operation)
        return "ALU指令占比";
    if (field == &DicArchCounterStats::mdu_complete)
        return "MDU指令占比";
    if (field == &DicArchCounterStats::memory_access_operation)
        return "访存指令占比";
    if (field == &DicArchCounterStats::control_status_register_operation)
        return "CSR指令占比";
    if (field == &DicArchCounterStats::branch_operation)
        return "分支/跳转指令占比";
    return CounterDescriptorFor(field).print_label;
}

export struct DicArchCounterStatisticsReport
{
    std::vector<std::string> lines;
    // 实际被文本格式化器读取的字段；供一致性测试检查“新增计数器漏打印”。
    std::array<bool, CounterDescriptorCount> printed{};
};

export DicArchCounterStatisticsReport FormatDicArchCounterStatistics(
    const DicArchCounterStats &stats,
    std::size_t total_cycles,
    std::size_t retired_instructions);
}
