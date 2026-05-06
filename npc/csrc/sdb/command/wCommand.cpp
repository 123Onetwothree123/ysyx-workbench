#include "wCommand.hpp"
#include <print>
#include <cstdint>
#include <string>
#include "tools/Expressions/Expressions.hpp"
#include "SDBCommandUtils.hpp"
#include "NPCEvaluationContext.hpp"
std::string_view wCommand::Name() const noexcept
{
    return "w";
}
SDBCommandUsageList wCommand::Usage() const noexcept
{
    static constexpr SDBCommandUsage Entries[]{
        {"EXPR", "设置表达式监视点，当表达式的值变化时暂停执行"},
    };
    return Entries;
}
SDBCommandResult wCommand::Execute(SDBCommandContext &Context, std::string_view Args)
{
    (void)Context;
    Args = SDBTrimLeft(Args);
    if (Args.empty())
    {
        std::println("用法：w EXPR");
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
        const auto InitialValue{Result.value()};
        //真不想用普通指针，但是因为生命周期是pool管理，uni是会出现双重所有权的问题，shared是不仅解决不了问题还有计数器额外开销
        auto *wp{GetGlobalWatchpointPool().CreateWatchpoint(std::string(Args), InitialValue)};
        if (wp)
        {
            std::println("监视点 {}: {}", wp->GetNO(), Args);
        }
        else
        {
            std::println("跑个蛋啊，监视点数量已经满了");
        }
    }
    else
    {
        std::println("表达式写错了：{}", Result.error());
    }
    return SDBCommandResult::Continue;
}
