#include "command/qCommand.hpp"
std::string_view qCommand::Name() const noexcept
{
    return "q";
}

SDBCommandUsageList qCommand::Usage() const noexcept
{
    static constexpr SDBCommandUsage Entries[]{
        {"", "退出 sdb"},
    };
    return Entries;
}

SDBCommandResult qCommand::Execute(SDBCommandContext &Context, std::string_view Args)
{
    (void)Context;
    (void)Args;
    return SDBCommandResult::Quit;
}
