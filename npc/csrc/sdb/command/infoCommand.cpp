#include "command/infoCommand.hpp"
#include "command/WatchpointPool.hpp"
#include "../SDBCommandContext.hpp"
#include <print>

static void PrintGPR(DUT &dut);
static void PrintWatchpoints();

std::string_view infoCommand::name() const noexcept
{
    return "info";
}
SDBCommandUsageList infoCommand::usage() const noexcept
{
    static const SDBCommandUsage entries[]{
        {"r", "打印通用寄存器"},
        {"w", "打印监视点状态"}};
    return entries;
}
SDBCommandResult infoCommand::execute(SDBCommandContext &context, std::string_view args)
{
    if (args == "r")
    {
        PrintGPR(context.GetDUT());
        return SDBCommandResult::Continue;
    }
    if (args == "w")
    {
        GetGlobalWatchpointPool().PrintAllWatchpoints();
        return SDBCommandResult::Continue;
    }
    std::println("用法：info r 或 info w");
    return SDBCommandResult::Continue;
}
