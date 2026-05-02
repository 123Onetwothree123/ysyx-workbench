#include "command/iringbufCommand.hpp"
#include "itrace.hpp"
#include <print>
std::string_view iringbufCommand::Name() const noexcept
{
    return "iringbuf";
}
SDBCommandUsageList iringbufCommand::Usage() const noexcept
{
    static constexpr SDBCommandUsage Entries[]{
        {"", "打印最近记录的指令环形缓冲区"},
    };
    return Entries;
}
SDBCommandResult iringbufCommand::Execute(SDBCommandContext &Context, std::string_view Args)
{
    (void)Context;
    if (!Args.empty())
    {
        std::println("用法：iringbuf");
        return SDBCommandResult::Continue;
    }
    PrintIringbuf(0);
    return SDBCommandResult::Continue;
}
