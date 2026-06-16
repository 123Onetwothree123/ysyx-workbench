#include "wCommand.hpp"
#include <print>
#include <cstdint>
#include <string>
#include "SDBCommandUtils.hpp"
std::string_view wCommand::name() const noexcept
{
    return "w";
}
SDBCommandUsageList wCommand::usage() const noexcept
{
    static const SDBCommandUsage entries[]{
        {"EXPR", "设置表达式监视点，当表达式的值变化时暂停执行"},
    };
    return entries;
}
SDBCommandResult wCommand::execute(SDBCommandContext &context, std::string_view args)
{
    static_cast<void>(context);
    args = SDBTrimLeft(args);
    // 没写完
}
