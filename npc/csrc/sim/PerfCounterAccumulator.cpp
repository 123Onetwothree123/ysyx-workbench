module;
module npc.PerfCounterAccumulator;
import std;
import npc.config;
import npc.DicArchCounterStats;
import npc.PerfCounterDescriptors;
namespace npc
{
void PerfCounterAccumulator::Reset()
{
    stats_ = {};
}
void PerfCounterAccumulator::Sample(const PerfSignals &signals)
{
    for (const auto &descriptor : CounterDescriptors)
    {
        if (descriptor.enabled &&
            descriptor.sample_kind == CounterSampleKind::Signal)
        {
            stats_.*descriptor.field += descriptor.sample(signals);
        }
    }
    // 事件分类按单计数器开关合取；开关和 event_bit 来自同一张表。
    unsigned event_mask = 0;
    for (const auto &descriptor : CounterDescriptors)
    {
        if (descriptor.enabled &&
            descriptor.sample_kind == CounterSampleKind::EventClassified)
        {
            event_mask |= descriptor.event_bit;
        }
    }
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
