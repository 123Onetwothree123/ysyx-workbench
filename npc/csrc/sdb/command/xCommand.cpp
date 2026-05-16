#include "command/xCommand.hpp"
#include "NPCEvaluationContext.hpp"
#include "SDBMemory.hpp"
#include "command/SDBCommandUtils.hpp"
#include "memory.hpp"
#include "tools/Expressions/Expressions.hpp"

#include <charconv>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <limits>
#include <print>
#include <string_view>
std::string_view xCommand::Name() const noexcept
{
    return "x";
}
SDBCommandUsageList xCommand::Usage() const noexcept
{
    static constexpr SDBCommandUsage Entries[]{
        {"N EXPR", "从表达式地址开始看N个4字节数据，默认就是这个"},
        {"Nb EXPR", "从表达式地址开始看N个1字节数据"},
        {"Nh EXPR", "从表达式地址开始看N个2字节数据"},
        {"Nw EXPR", "从表达式地址开始看N个4字节数据"},
    };
    return Entries;
}
SDBCommandResult xCommand::Execute(SDBCommandContext &Context, std::string_view Args)
{
    static_cast<void>(Context);
    Args = SDBTrimLeft(Args); // 去掉前导空白
    if (Args.empty())
    {
        std::println("用法：x N[b|h|w] EXPR");
        return SDBCommandResult::Continue;
    }
    auto Count{std::size_t{0}}; // 扫描的数量
    const auto CountResult{std::from_chars(Args.data(), Args.data() + Args.size(), Count, 10)};
    if (CountResult.ec != std::errc() || Count == 0)
    {
        std::println("错误：缺少有效的扫描数量 N");
        std::println("用法：x N[b|h|w] EXPR");
        return SDBCommandResult::Continue;
    }
    Args.remove_prefix(static_cast<std::size_t>(CountResult.ptr - Args.data())); // 移除已经解析的数量部分
    Args = SDBTrimLeft(Args);
    auto UnitSize{std::size_t{4}}; // 默认按字读取，和原C版一致
    if (!Args.empty())
    {
        switch (Args.front())
        {
        case 'b':
        {
            UnitSize = 1;
            Args.remove_prefix(1);
            break;
        }
        case 'h':
        {
            UnitSize = 2;
            Args.remove_prefix(1);
            break;
        }
        case 'w':
        {
            UnitSize = 4;
            Args.remove_prefix(1);
            break;
        }
        default:
        {
            break;
        }
        }
    }
    Args = SDBTrimLeft(Args);
    if (Args.empty())
    {
        std::println("错误：缺少地址表达式");
        std::println("用法：x N[b|h|w] EXPR");
        return SDBCommandResult::Continue;
    }
    Expressions ExpressionsEngine;
    NPCEvaluationContext EvalContext;
    const auto AddressResult{ExpressionsEngine.Evaluate(Args, EvalContext)};
    if (!AddressResult)
    {
        std::println("表达式错误：{}", AddressResult.error());
        return SDBCommandResult::Continue;
    }
    const auto StartAddress{AddressResult.value()};
    if (Count > std::numeric_limits<std::uint32_t>::max() / UnitSize)
    {
        std::println("错误：请求范围过大");
        return SDBCommandResult::Continue;
    }
    const auto TotalBytes{static_cast<std::uint32_t>(Count * UnitSize)};
    if (StartAddress > std::numeric_limits<std::uint32_t>::max() - (TotalBytes - 1))
    {
        std::println("错误：地址范围溢出");
        return SDBCommandResult::Continue;
    }
    std::println("正在扫描 {} 个项目（每个 {} 字节），从 0x{:08x} 到 0x{:08x}：",
                 Count, UnitSize, StartAddress, StartAddress + TotalBytes - 1);

    for (std::size_t Index{0}; Index < Count; ++Index)
    {
        const auto CurrentAddress{StartAddress + static_cast<std::uint32_t>(Index * UnitSize)};
        const auto ValueOpt{NPCMemoryReadSafe(CurrentAddress, UnitSize)};
        if (!ValueOpt.has_value())
        {
            std::println(std::cerr, "错误：在 0x{:08x} 处读取内存失败，扫描停止", CurrentAddress);
            break;
        }
        const auto Value{ValueOpt.value()};
        switch (UnitSize)
        {
        case 1:
        {
            std::println("0x{:08x}: 0x{:02x}", CurrentAddress, Value & 0xffu);
            break;
        }
        case 2:
        {
            std::println("0x{:08x}: 0x{:04x}", CurrentAddress, Value & 0xffffu);
            break;
        }
        case 4:
        {
            std::println("0x{:08x}: 0x{:08x}", CurrentAddress, Value);
            break;
        }
        default:
        {
            break;
        }
        }
    }
    return SDBCommandResult::Continue;
}
