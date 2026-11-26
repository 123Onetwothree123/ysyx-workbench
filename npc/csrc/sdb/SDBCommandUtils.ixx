export module npc.sdb.SDBCommandUtils;
import std;
import npc.Output;
import npc.DUT;

export [[nodiscard]] std::string_view SDBTrimLeft(std::string_view text);  // 去掉字符串左边的空白字符
export [[nodiscard]] std::string_view SDBTrimRight(std::string_view text); // 去掉字符串右边的空白字符
// 将一行命令分成命令名和参数两部分，命令名是第一个连续的非空白字符序列，参数是剩下的部分（去掉前导空白）
export [[nodiscard]] std::pair<std::string_view, std::string_view> SDBSplitCommandLine(std::string_view line);
// 检查表达式括号是否匹配（只检查圆括号）
export [[nodiscard]] bool SDBValidateExpressionSyntax(std::string_view expression);

// 无参数命令收到参数时打印用法并返回 true，调用方据此提前返回 Continue。
export [[nodiscard]] bool
    SDBRejectUnexpectedArguments(std::string_view arguments, std::string_view usage, Output &out);

export template <typename Integer>
struct SDBIntegerArgument
{
    Integer value;
    std::string_view remainder; // 整数之后的内容（已去掉前导空白）
};

// 去掉前导空白后按十进制解析整数，并给出整数之后剩余的文本。
// 用模板保留调用方的整数类型，避免把 d 的 size_t 与 si/history 的
// ptrdiff_t 统一成一个类型后改变符号或范围边界上的行为；上下界和
// 尾部垃圾文案仍由各命令自己判断，顺序与原来一致。
export template <typename Integer>
[[nodiscard]] std::expected<SDBIntegerArgument<Integer>, std::errc>
SDBParseIntegerArgument(std::string_view arguments)
{
    arguments = SDBTrimLeft(arguments);
    if (arguments.empty())
    {
        return std::unexpected(std::errc::invalid_argument);
    }
    auto value{Integer{0}};
    const auto result{std::from_chars(arguments.data(), arguments.data() + arguments.size(), value, 10)};
    if (result.ec != std::errc())
    {
        return std::unexpected(result.ec);
    }
    const auto remainder{SDBTrimLeft(std::string_view{
        result.ptr, static_cast<std::size_t>(arguments.data() + arguments.size() - result.ptr)})};
    return SDBIntegerArgument<Integer>{value, remainder};
}

// 求值已经过参数检查的表达式；错误文本为求值器给出的原因。
export [[nodiscard]] std::expected<std::uint64_t, std::string>
    SDBEvaluateExpressionValue(std::string_view expression, DUT &dut);

// 依次完成去空白、空参数检查、括号检查，再求值。
// 空参数和括号错误的文案由调用方提供；求值失败统一带“表达式错误：”前缀。
export [[nodiscard]] std::expected<std::uint64_t, std::string>
    SDBEvaluateExpression(std::string_view arguments,
                          DUT &dut,
                          std::string_view EmptyArgumentsError,
                          std::string_view SyntaxError);
