#include "command/SDBCommandUtils.hpp"
#include <VRV32E32Reg.h>
#include "difftest.hpp"
std::string_view SDBTrimLeft(std::string_view Text)
{
    auto First{Text.find_first_not_of(" \t")}; // 找到第一个非空白字符的位置
    if (First == std::string_view::npos)       // 如果没有非空白字符，返回空字符串
    {
        return {};
    }
    Text.remove_prefix(First); // 移除前导空白字符
    return Text;
}
std::string_view SDBTrimRight(std::string_view Text)
{
    auto Last{Text.find_last_not_of(" \t")}; // 找到最后一个非空白字符的位置
    if (Last == std::string_view::npos)      // 如果没有非空白字符，返回空字符串
    {
        return {};
    }
    Text.remove_suffix(Text.size() - Last - 1); // 移除尾部空白字符
    return Text;
}
std::pair<std::string_view, std::string_view> SDBSplitCommandLine(std::string_view Line)
{
    Line = SDBTrimLeft(Line);                   // 先去掉前导空白
    auto CommandEnd{Line.find_first_of(" \t")}; // 找到命令名的结尾位置
    if (CommandEnd == std::string_view::npos)   // 如果没有空白字符，说明整行都是命令名，没有参数
    {
        return {Line, {}};
    }
    const auto Name{Line.substr(0, CommandEnd)}; // get到命令名
    Line.remove_prefix(CommandEnd);              // 本来不知道怎么做，然后GitHub copilot给的是可以移除命令名和它后面的空白，剩下的就是参数部分
    return {Name, SDBTrimRight(SDBTrimLeft(Line))}; // 去掉参数部分的前导和尾部空白
}
void SDBStepCycle(VRV32E32Reg &Top)
{
    Top.clk = 0;
    Top.eval();
    Top.clk = 1;
    Top.eval();
    DifftestStep(Top);
}
bool SDBValidateExpressionSyntax(std::string_view Expression)
{
    if (Expression.empty())
    {
        return false;
    }
    auto paren_count{0};
    for (const char ch : Expression)
    {
        if (ch == '(')
        {
            ++paren_count;
        }
        else if (ch == ')')
        {
            --paren_count;
        }
        if (paren_count < 0)
        {
            return false; // 出现右括号多于左括号的情况
        }
    }
    return paren_count == 0;
}
