#include "command/setCommand.hpp"
#include "NPCEvaluationContext.hpp"
#include "command/SDBCommandUtils.hpp"
#include "tools/Expressions/Expressions.hpp"
#include "tools/Expressions/RegisterName.hpp"
#include <VRV32E32Reg.h>
#include <cstdint>
#include <print>
#include <string>
#include <string_view>
#include <utility>
namespace
{
    constexpr std::string_view UsageText{"set reg <name> <expr>"};
    std::pair<std::string_view, std::string_view> TakeToken(std::string_view Text)
    {
        Text = SDBTrimLeft(Text);
        const auto TokenEnd{Text.find_first_of(" \t")};
        if (TokenEnd == std::string_view::npos)
        {
            return {Text, {}};
        }
        auto Token{Text.substr(0, TokenEnd)};
        Text.remove_prefix(TokenEnd);
        return {Token, SDBTrimLeft(Text)};
    }
    void ClearDebugWriteInputs(VRV32E32Reg &Top)
    {
        Top.sdb_debug_clk = 0;
        Top.sdb_pc_write_en = 0;
        Top.sdb_pc_write_data = 0;
        Top.sdb_gpr_write_en = 0;
        Top.sdb_gpr_write_addr = 0;
        Top.sdb_gpr_write_data = 0;
    }
    void CommitDebugWrite(VRV32E32Reg &Top)
    {
        Top.sdb_debug_clk = 0;
        Top.eval();
        Top.sdb_debug_clk = 1;
        Top.eval();
        Top.sdb_debug_clk = 0;
        Top.eval();
    }
    void WriteProgramCounter(VRV32E32Reg &Top, std::uint32_t Value)
    {
        Top.sdb_pc_write_data = Value;
        Top.sdb_pc_write_en = 1;
        CommitDebugWrite(Top);
        ClearDebugWriteInputs(Top);
    }
    bool WriteGeneralRegister(VRV32E32Reg &Top, std::uint32_t Index, std::uint32_t Value)
    {
        if (Index == 0 || Index >= 32)
        {
            return false;
        }
        Top.sdb_gpr_write_addr = static_cast<unsigned char>(Index);
        Top.sdb_gpr_write_data = Value;
        Top.sdb_gpr_write_en = 1;
        CommitDebugWrite(Top);
        ClearDebugWriteInputs(Top);
        return true;
    }
} // namespace
std::string_view setCommand::Name() const noexcept
{
    return "set";
}
SDBCommandUsageList setCommand::Usage() const noexcept
{
    std::println("set的话目前是只支持reg，后面memory功能，理论上实现起来不难，但是这太麻烦了，目前就先不做了，反正也不是osoc的文档要求部分功能");
    static constexpr SDBCommandUsage Entries[]{
        {"reg pc <expr>", "把pc改成表达式算出来的值"},
        {"reg x1 <expr>", "把x1到x31改成表达式算出来的值，x0不能改"},
        {"reg ra <expr>", "也可以用ABI寄存器名，比如ra/sp/a0"},
    };
    return Entries;
}
SDBCommandResult setCommand::Execute(SDBCommandContext &Context, std::string_view Args)
{
    Args = SDBTrimRight(SDBTrimLeft(Args));
    if (Args.empty())
    {
        std::println("cmd_set检测到参数为空，即无参数");
        std::println("用法：{}", UsageText);
        return SDBCommandResult::Continue;
    }
    const auto [SubCommand, RestAfterSubCommand]{TakeToken(Args)};
    if (SubCommand.empty() || RestAfterSubCommand.empty())
    {
        std::println("错误：参数不完整");
        std::println("用法：{}", UsageText);
        return SDBCommandResult::Continue;
    }
    if (SubCommand != "reg")
    {
        std::println("错误：未知的 set 子命令 '{}'", SubCommand);
        std::println("当前只支持：{}", UsageText);
        return SDBCommandResult::Continue;
    }
    const auto [RegisterName, ExpressionTextRaw]{TakeToken(RestAfterSubCommand)};
    auto ExpressionText{SDBTrimRight(ExpressionTextRaw)};
    if (RegisterName.empty())
    {
        std::println("缺少寄存器名");
        std::println("用法：{}", UsageText);
        return SDBCommandResult::Continue;
    }
    if (ExpressionText.empty())
    {
        std::println("错误：缺少表达式");
        std::println("用法：{}", UsageText);
        return SDBCommandResult::Continue;
    }
    if (!SDBValidateExpressionSyntax(ExpressionText))
    {
        std::println("表达式错误：括号不匹配");
        return SDBCommandResult::Continue;
    }
    Expressions ExpressionsEngine;
    NPCEvaluationContext EvaluationContext;
    const auto ValueResult{ExpressionsEngine.Evaluate(ExpressionText, EvaluationContext)};
    if (!ValueResult)
    {
        std::println("表达式求值失败：{}", ValueResult.error());
        return SDBCommandResult::Continue;
    }
    const auto Value{static_cast<std::uint32_t>(ValueResult.value())};
    if (IsProgramCounterName(RegisterName))
    {
        WriteProgramCounter(Context.GetTop(), Value);
        return SDBCommandResult::Continue;
    }
    const auto RegIndex{RegisterNameToIndex(RegisterName)};
    if (!RegIndex)
    {
        std::println("错误：无效的寄存器名 '{}'", RegisterName);
        return SDBCommandResult::Continue;
    }
    if (*RegIndex == 0)
    {
        std::println("错误：x0/$0 是只读寄存器，不能修改");
        return SDBCommandResult::Continue;
    }
    if (!WriteGeneralRegister(Context.GetTop(), *RegIndex, Value))
    {
        std::println("错误：写入寄存器 '{}' 失败", RegisterName);
    }
    return SDBCommandResult::Continue;
}
