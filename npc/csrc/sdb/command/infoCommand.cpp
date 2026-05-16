#include "command/infoCommand.hpp"
#include "SDBDPI.hpp"
#include "command/WatchpointPool.hpp"
#include <print>
std::string_view infoCommand::Name() const noexcept
{
    return "info";
}
SDBCommandUsageList infoCommand::Usage() const noexcept
{
    static constexpr SDBCommandUsage Entries[]{
        {"r", "打印通用寄存器"},
        {"w", "打印监视点状态"},
    };
    return Entries;
}
SDBCommandResult infoCommand::Execute(SDBCommandContext &Context, std::string_view Args)
{
    static_cast<void>(Context);
    if (Args == "r")
    {
        PrintGPR();
        return SDBCommandResult::Continue;
    }
    if (Args == "w")
    {
        PrintWatchpoints();
        return SDBCommandResult::Continue;
    }
    std::println("用法：info r 或 info w");
    return SDBCommandResult::Continue;
}
