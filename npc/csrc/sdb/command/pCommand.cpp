module;
module npc.sdb.command.pCommand;
import npc.SimulationContext;
import npc.Output;
import npc.config;
import npc.sdb.SDBCommandUtils;
import npc.DUT;

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
    auto &out{context.GetSimulation().Out()};
    const auto result{SDBEvaluateExpression(args, context.GetDUT(), "参数是空的", "表达式错误：括号不匹配")};
    if (result)
    {
        auto UnsignedValue{*result};
        // RV64 的表达式结果是 64 位，不能按 32 位有符号数解释。
        auto signedVal{static_cast<std::int64_t>(UnsignedValue)};
        out.Info("有符号（十进制）：{}", signedVal);
        out.Info("无符号（十进制）：{}", UnsignedValue);
        out.Info("十六进制：        0x{:0{}x}", UnsignedValue,
                     npc::XHexDigits);
    }
    else
    {
        out.Error("{}", result.error());
    }
    return SDBCommandResult::Continue;
}
