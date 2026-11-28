module;
export module npc.PerfCounterAccumulator;
import std;
import npc.config;
import npc.DicArchCounterStats;
// PerfSignals 与计数器 descriptor 表都在 npc.PerfCounterDescriptors 中，
// 这里只保留累加器状态机，并重新导出信号类型供 DUT 使用。
export import npc.PerfCounterDescriptors;
export namespace npc
{
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
