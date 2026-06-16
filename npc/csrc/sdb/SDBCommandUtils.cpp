#include "SDBCommandUtils.hpp"
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
[[nodiscard]] std::pair<std::string_view, std::string_view> SDBSplitCommandLine(std::string_view line){
    line = SDBTrimLeft(line);
    auto CommandEnd{line.find_first_of(" \t")}; // 找到命令名的结尾位置
     if (CommandEnd == std::string_view::npos)   // 如果没有空白字符，说明整行都是命令名，没有参数
    {
        return {line, {}};
    }
    const auto CommandName{line.substr(0, CommandEnd)};
    const auto name{line.substr(0, CommandEnd)};
    line.remove_prefix(CommandEnd);
    return{name, SDBTrimRight(SDBTrimLeft(line))};
}
[[nodiscard]] bool SDBValidateExpressionSyntax(std::string_view expression){
    //先留空，后面再写
    return false;
}