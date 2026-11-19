module;
export module npc.PerfCounterAccumulator;
import std;
import npc.config;
import npc.DicArchCounterStats;
export namespace npc
{
// pre-edge 字段须在上升沿前采样，其余沿后读。
struct PerfSignals
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
class PerfCounterAccumulator
{
public:
    // 只清统计值，不清跨复位的 IRQ 延迟跟踪。
    void Reset();
    void Sample(const PerfSignals &signals);
    [[nodiscard]] const DicArchCounterStats &Stats() const noexcept;
    [[nodiscard]] DicArchCounterStats &Stats() noexcept;
private:
    DicArchCounterStats stats_{};
    // 从 IRQ pending 首次拉高跟踪到 trap 入口。
    std::size_t irq_latency_current_{0};
    bool irq_latency_active_{false};
};
}
