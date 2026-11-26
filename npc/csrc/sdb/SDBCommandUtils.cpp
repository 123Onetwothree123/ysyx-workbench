module npc.sdb.SDBCommandUtils;
import npc.expressions.expressions;
import npc.sdb.NPCEvaluationContext;
[[nodiscard]] std::string_view SDBTrimLeft(std::string_view text)
{
    auto first{text.find_first_not_of(" \t")};
    if (first == std::string_view::npos)
    {
        return {};
    }
    text.remove_prefix(first);
    return text;
}
[[nodiscard]] std::string_view SDBTrimRight(std::string_view text)
{
    auto last{text.find_last_not_of(" \t")};
    if (last == std::string_view::npos)
    {
        return {};
    }
    text.remove_suffix(text.size() - last - 1);
    return text;
}
[[nodiscard]] std::pair<std::string_view, std::string_view> SDBSplitCommandLine(std::string_view line)
{
    line = SDBTrimLeft(line);
    auto CommandEnd{line.find_first_of(" \t")}; // 找到命令名的结尾位置
    if (CommandEnd == std::string_view::npos)   // 如果没有空白字符，说明整行都是命令名，没有参数
    {
        return {line, {}};
    }
    const auto CommandName{line.substr(0, CommandEnd)};
    line.remove_prefix(CommandEnd);
    return {CommandName, SDBTrimRight(SDBTrimLeft(line))};
}
[[nodiscard]] bool SDBValidateExpressionSyntax(std::string_view expression)
{
    if (expression.empty())
    {
        return false;
    }
    auto ParenCount{0};
    for (const char character : expression)
    {
        if (character == '(')
        {
            ++ParenCount;
        }
        else if (character == ')')
        {
            --ParenCount;
        }
        if (ParenCount < 0)
        {
            return false;
        }
    }
    return ParenCount == 0;
}
[[nodiscard]] bool SDBRejectUnexpectedArguments(std::string_view arguments,
                                               std::string_view usage,
                                               Output &out)
{
    if (arguments.empty())
    {
        return false;
    }
    out.Info("{}", usage);
    return true;
}
[[nodiscard]] std::expected<std::uint64_t, std::string>
SDBEvaluateExpressionValue(std::string_view expression, DUT &dut)
{
    expressions ExpressionEngine;
    NPCEvaluationContext EvaluationContext{dut};
    return ExpressionEngine.evaluate(expression, EvaluationContext);
}
[[nodiscard]] std::expected<std::uint64_t, std::string>
SDBEvaluateExpression(std::string_view arguments,
                      DUT &dut,
                      std::string_view EmptyArgumentsError,
                      std::string_view SyntaxError)
{
    arguments = SDBTrimLeft(arguments);
    if (arguments.empty())
    {
        return std::unexpected(std::string{EmptyArgumentsError});
    }
    if (!SDBValidateExpressionSyntax(arguments))
    {
        return std::unexpected(std::string{SyntaxError});
    }
    auto result{SDBEvaluateExpressionValue(arguments, dut)};
    if (!result)
    {
        return std::unexpected(std::format("表达式错误：{}", result.error()));
    }
    return *result;
}
