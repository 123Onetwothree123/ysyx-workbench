#include "command/cCommand.hpp"
#include "command/SDBCommandUtils.hpp"
#include "command/WatchpointPool.hpp"
#include <verilated.h>
extern bool npc_halted;
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
    (void)Args;
    while (!Verilated::gotFinish() && !npc_halted)
    {
        SDBStepCycle(Context.GetTop());
        ++Context.GetCycles();
        if (GetGlobalWatchpointPool().CheckAll())
        {
            npc_halted = true;
            std::println("程序因监视点变化而停止。");
            break;
        }
    }
    return SDBCommandResult::Continue;
}
