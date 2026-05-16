#include "command/clearCommand.hpp"
#include <print>
std::string_view clearCommand::Name() const noexcept
{
    return "clear";
}
SDBCommandUsageList clearCommand::Usage() const noexcept
{
    static constexpr SDBCommandUsage Entries[]{
        {"", "清除终端屏幕"},
    };
    return Entries;
}
SDBCommandResult clearCommand::Execute(SDBCommandContext &Context, std::string_view Args)
{
    static_cast<void>(Context);
    static_cast<void>(Args);
    std::print("\033[H\033[J");
    std::fflush(stdout);
    return SDBCommandResult::Continue;
}
