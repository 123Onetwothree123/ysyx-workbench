module npc.sdb.command.wCommand;
import npc.Output;
import npc.sdb.SDBCommandUtils;
import npc.expressions.expressions;
import npc.sdb.NPCEvaluationContext;
import npc.DUT;
import npc.sdb.command.WatchpointPool;
import npc.SimulationContext;

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
    auto &out{context.GetSimulation().Out()};
    args = SDBTrimLeft(args);
    if (args.empty())
    {
        out.Error("没写参数");
        return SDBCommandResult::Continue;
    }
    if (!SDBValidateExpressionSyntax(args))
    {
        out.Error("括号不匹配");
        return SDBCommandResult::Continue;
    }
    expressions expression;
    NPCEvaluationContext EvaluationContetx{context.GetDUT()};
    auto result{expression.evaluate(args, EvaluationContetx)};
    if (result)
    {
        auto *wp{context.GetSimulation().Watchpoints().CreateWatchpoint(std::string(args), *result)};
        if (wp)
        {
            out.Info("监视点 {}: {}", wp->GetNO(), args);
        }
        else
        {
            out.Error("监视点数量满了");
        }
    }
    else
    {
        out.Error("表达式错误：{}", result.error());
    }
    return SDBCommandResult::Continue;
}
