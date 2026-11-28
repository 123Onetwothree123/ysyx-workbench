module;
module npc.NPCTrap;
import npc.Output;
import npc.config;
import npc.trace.itrace;
import npc.SimulationContext;
import npc.PerfCounterDescriptors;
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
    // 文本行的分组、标签与格式由 npc.PerfCounterDescriptors 的单一
    // descriptor 表生成，NPCTrap 只负责把行送到 Output。
    const auto report{npc::FormatDicArchCounterStatistics(
        stats, total_cycles, retired_instructions)};
    for (const auto &line : report.lines)
    {
        out.Info("{}", line);
    }
}
