#include "command/pCommand.hpp"
#include "command/SDBCommandUtils.hpp"
#include "NPCEvaluationContext.hpp"
#include "tools/Expressions/Expressions.hpp"
#include <cstddef>
#include <cstdint>
#include <print>
#include <string>
#include <string_view>
std::string_view pCommand::Name() const noexcept
{
    return "p";
}
SDBCommandUsageList pCommand::Usage() const noexcept
{
    static constexpr SDBCommandUsage Entries[]{
        {"EXPR", "计算表达式（支持$pc、x0-x31、ABI寄存器别名、内存read8/read16/read32、算术运算）"},
    };
    return Entries;
}
SDBCommandResult pCommand::Execute(SDBCommandContext &Context, std::string_view Args)
{
    static_cast<void>(Context);
    Args = SDBTrimLeft(Args); // 去掉前导空白
    if (Args.empty())
    {
        std::println("用法：p EXPR");
        return SDBCommandResult::Continue;
    }
    if (!SDBValidateExpressionSyntax(Args))
    {
        std::println("表达式错误：括号不匹配");
        return SDBCommandResult::Continue;
    }
    Expressions expressions;
    NPCEvaluationContext EvaluationContext;
    auto Result{expressions.Evaluate(Args, EvaluationContext)};
    if (Result)
    {
        const auto unsigned_val{Result.value()};
        const auto signed_val{static_cast<std::int32_t>(unsigned_val)};
        std::println("有符号（十进制）：   {}", signed_val);
        std::println("无符号（十进制）： {}", unsigned_val);
        std::println("十六进制：            0x{:08x}", unsigned_val);
    }
    else
    {
        std::println("表达式错误：{}", Result.error());
    }
    return SDBCommandResult::Continue;
}
