#include "command/cCommand.hpp"
#include "NPCTrap.hpp"
#include "command/SDBCommandUtils.hpp"
#include "command/WatchpointPool.hpp"
#include <verilated.h>
std::string_view cCommand::Name() const noexcept
{
    return "c";
}
SDBCommandUsageList cCommand::Usage() const noexcept
{
    static constexpr SDBCommandUsage Entries[]{
        {"", "继续运行直到结束"},
    };
    return Entries;
}
SDBCommandResult cCommand::Execute(SDBCommandContext &Context, std::string_view Args)
{
    static_cast<void>(Args);
    while (!Verilated::gotFinish() && !NPCTrap::HasHalted())
    {
        SDBStepCycle(Context.GetTop());
        ++Context.GetCycles();
        if (GetGlobalWatchpointPool().CheckAll())
        {
            NPCTrap::Stop();
            std::println("程序因监视点变化而停止。");
            break;
        }
    }
    return SDBCommandResult::Continue;
}
