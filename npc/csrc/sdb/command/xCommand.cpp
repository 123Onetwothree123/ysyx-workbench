#include "command/xCommand.hpp"
#include "SDBCommandUtils.hpp"
#include <charconv>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <limits>
#include <print>
#include <string_view>
std::string_view xCommand::name() const noexcept
{
    return "x";
}
SDBCommandUsageList xCommand::usage() const noexcept
{
    static const SDBCommandUsage entries[]{
        {"N EXPR", "从表达式地址开始看N个4字节数据，默认就是这个"},
        {"Nb EXPR", "从表达式地址开始看N个1字节数据"},
        {"Nh EXPR", "从表达式地址开始看N个2字节数据"},
        {"Nw EXPR", "从表达式地址开始看N个4字节数据"},
    };
    return entries;
}
SDBCommandResult xCommand::execute(SDBCommandContext &context, std::string_view args)
{
    static_cast<void>(context);
    args = SDBTrimLeft(args);
    // 没写完
}
