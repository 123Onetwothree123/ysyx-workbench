module npc.SimulationContext;
import npc.Output;
namespace
{
void WriteTraceLine(std::string_view Line)
{
    SimulationContext::Active().Out().Info("{}", Line);
}
}
SimulationContext *SimulationContext::active{nullptr};
SimulationContext::SimulationContext()
{
    watchpoints.SetOutput(output);
    ftrace.SetOutput(&WriteTraceLine);
}
SimulationContext::~SimulationContext()
{
    if (active == this)
        ClearActive();
}
void SimulationContext::SetActive(SimulationContext &Context)
{
    active = &Context;
}
void SimulationContext::ClearActive() noexcept
{
    active = nullptr;
}
SimulationContext &SimulationContext::Active()
{
    if (active == nullptr)
    {
        throw std::logic_error{
            "SimulationContext::Active(): 未设置活动仿真上下文"};
    }
    return *active;
}
void TrapState::Halt(npc::XWord PC, std::uint32_t Code) noexcept
{
    if (halted && code != 0 && Code == 0)
        return;
    halted = true;
    pc = PC;
    code = Code;
}
Output &SimulationContext::Out() noexcept
{
    return output;
}
std::vector<std::uint8_t> &SimulationContext::Flash() noexcept
{
    return flash;
}
std::vector<std::uint8_t> &SimulationContext::Mrom() noexcept
{
    return mrom;
}
WatchpointPool &SimulationContext::Watchpoints() noexcept
{
    return watchpoints;
}
Ftrace &SimulationContext::Trace() noexcept
{
    return ftrace;
}
TrapState &SimulationContext::Trap() noexcept
{
    return trap;
}
