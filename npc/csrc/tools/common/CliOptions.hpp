#pragma once
#include <charconv>
#include <cstdint>
#include <cstdio>
#include <optional>
#include <print>
#include <string>
#include <string_view>
#include <utility>
#include <vector>
namespace common
{
// 十进制无符号；整串必须都是数字
inline auto ParseUint(std::string_view text, unsigned& out) -> bool
{
    std::uint32_t value{};
    const auto* begin{text.data()};
    const auto* end{begin + text.size()};
    const auto [ptr, ec]{std::from_chars(begin, end, value, 10)};
    if (ec != std::errc{} || ptr != end || ptr == begin)
        return false;
    out = value;
    return true;
}
struct OptionSpec
{
    // 帮助行，不含前导两空格
    std::string usage_line;
    std::string_view short_name; // "-b"，可为空
    std::string_view long_name;  // "--block-size"
    bool takes_value{true};
};
class CliOptions
{
public:
    CliOptions(std::string_view program, std::string_view usage_line)
        : program_{program}, usage_line_{usage_line}
    {
    }
    void Add(OptionSpec spec)
    {
        specs_.push_back(std::move(spec));
        values_.emplace_back();
        provided_.push_back(false);
        flags_.push_back(false);
    }
    // false 表示直接退出，退出码见 exit_code。
    auto Parse(int argc, char const* const* argv, int& exit_code) -> bool
    {
        for (int i{1}; i < argc; ++i)
        {
            const std::string_view arg{argv[i]};
            if (arg == "-h" || arg == "--help")
            {
                PrintUsage(stdout);
                exit_code = 0;
                return false;
            }
            if (!arg.empty() && arg[0] == '-')
            {
                const auto index{Find(arg)};
                if (!index)
                {
                    std::println(stderr, "未知选项: {}", arg);
                    PrintUsage(stderr);
                    exit_code = 1;
                    return false;
                }
                if (specs_[*index].takes_value)
                {
                    if (i + 1 >= argc)
                    {
                        std::println(stderr, "错误: {} 缺少参数", arg);
                        exit_code = 1;
                        return false;
                    }
                    values_[*index] = argv[++i];
                    provided_[*index] = true;
                }
                else
                {
                    flags_[*index] = true;
                }
                continue;
            }
            positional_.push_back(arg);
        }
        exit_code = 0;
        return true;
    }
    [[nodiscard]] auto Has(std::string_view long_name) const -> bool
    {
        const auto index{Find(long_name)};
        return index && flags_[*index];
    }
    [[nodiscard]] auto Value(std::string_view long_name) const -> std::optional<std::string_view>
    {
        const auto index{Find(long_name)};
        if (!index || !provided_[*index])
            return std::nullopt;
        return values_[*index];
    }
    [[nodiscard]] auto Positional() const -> const std::vector<std::string_view>&
    {
        return positional_;
    }
    void PrintUsage(std::FILE* out) const
    {
        std::println(out, "用法: {} {}", program_, usage_line_);
        if (specs_.empty())
            return;
        std::println(out);
        std::println(out, "选项:");
        for (const auto& spec : specs_)
            std::println(out, "  {}", spec.usage_line);
    }
private:
    [[nodiscard]] auto Find(std::string_view name) const -> std::optional<std::size_t>
    {
        for (std::size_t i{0}; i < specs_.size(); ++i)
        {
            if ((!specs_[i].short_name.empty() && specs_[i].short_name == name)
                || specs_[i].long_name == name)
                return i;
        }
        return std::nullopt;
    }
    std::string_view program_;
    std::string_view usage_line_;
    std::vector<OptionSpec> specs_{};
    std::vector<std::string_view> values_{};
    std::vector<bool> provided_{};
    std::vector<bool> flags_{};
    std::vector<std::string_view> positional_{};
};
}
