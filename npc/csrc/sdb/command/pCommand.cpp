#include "pCommand.hpp"
#include "SDBCommandUtils.hpp"
#include "expressions.hpp"
#include <cstddef>
#include <cstdint>
#include <print>
#include <string>
#include <string_view>
#include "NPCEvaluationContext.hpp"
#include "SDBCommandContext.hpp"
std::string_view pCommand::name() const noexcept
{
    return "p";
}
SDBCommandUsageList pCommand::usage() const noexcept
{
    static const SDBCommandUsage entries[]{
        {"EXPR", "计算表达式（支持$pc、x0-x31、ABI寄存器别名、内存read8/read16/read32、算术运算）"},
    };
    return entries;
}
SDBCommandResult pCommand::execute(SDBCommandContext &context, std::string_view args)
{
    args = SDBTrimLeft(args); // 去掉前导空白
    if (args.empty())
    {
        std::println("参数是空的");
        return SDBCommandResult::Continue;
    }
    if (!SDBValidateExpressionSyntax(args))
    {
        std::println("表达式错误：括号不匹配");
        return SDBCommandResult::Continue;
    }
    expressions expression;
    NPCEvaluationContext EvaluationContext{context.GetDUT()};
    auto result{expression.evaluate(args, EvaluationContext)};
    if (result)
    {
        auto UnsignedValue{*result};
        auto signedVal{static_cast<std::int32_t>(UnsignedValue)};
        std::println("有符号（十进制）：{}", signedVal);
        std::println("无符号（十进制）：{}", UnsignedValue);
        std::println("十六进制：        0x{:08x}", UnsignedValue);
    }
    else
    {
        std::println("表达式错误：{}", result.error());
    }
    return SDBCommandResult::Continue;
}