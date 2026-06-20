export module npc.sdb.SDBCommandUtils;
import std;

export [[nodiscard]] std::string_view SDBTrimLeft(std::string_view text);  // 去掉字符串左边的空白字符
export [[nodiscard]] std::string_view SDBTrimRight(std::string_view text); // 去掉字符串右边的空白字符
// 将一行命令分成命令名和参数两部分，命令名是第一个连续的非空白字符序列，参数是剩下的部分（去掉前导空白）
export [[nodiscard]] std::pair<std::string_view, std::string_view> SDBSplitCommandLine(std::string_view line);
// 检查表达式括号是否匹配（只检查圆括号）
export [[nodiscard]] bool SDBValidateExpressionSyntax(std::string_view expression);
