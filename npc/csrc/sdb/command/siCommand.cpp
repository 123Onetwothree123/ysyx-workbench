#include "command/siCommand.hpp"
#include "NPCTrap.hpp"
#include "command/SDBCommandUtils.hpp"
#include "command/WatchpointPool.hpp"
#include <charconv>
#include <cstddef>
#include <print>
std::string_view siCommand::Name() const noexcept
{
    return "si";
}
SDBCommandUsageList siCommand::Usage() const noexcept
{
    static constexpr SDBCommandUsage Entries[]{
        {"[N]", "单步执行 N 条指令，默认 1 条"},
    };
    return Entries;
}
SDBCommandResult siCommand::Execute(SDBCommandContext &Context, std::string_view Args)
{
    auto Count{std::size_t{1}};
    Args = SDBTrimLeft(Args);
    if (!Args.empty())
    {
        auto ParsedCount{std::ptrdiff_t{0}};
        // Result是用来检查转换是否成功的，ParsedCount是用来存储转换结果的
        const auto Result{std::from_chars(Args.data(), Args.data() + Args.size(), ParsedCount, 10)};
        if (Result.ec != std::errc())
        {
            std::println("错误：无效的参数 '{0}'。用法：si [N]（N为正整数）", Args);
            return SDBCommandResult::Continue;
        }
        if (ParsedCount <= 0)
        {
            std::println("错误：步数必须是正整数，得到的是 {0}。用法：si [N]", ParsedCount);
            return SDBCommandResult::Continue;
        }
        // 检查尾部垃圾字符（如 "si 10abc"）
        auto Remainder{std::string_view{Result.ptr, static_cast<std::size_t>(Args.data() + Args.size() - Result.ptr)}};
        Remainder = SDBTrimLeft(Remainder);
        if (!Remainder.empty())
        {
            std::println("错误：参数中有尾部垃圾 '{0}'。用法：si [N]", Remainder);
            return SDBCommandResult::Continue;
        }
        Count = static_cast<std::size_t>(ParsedCount); // 如果转换成功并且是正数的话就使用这个值
    }
    if (NPCTrap::HasHalted())
    {
        std::println("NPC已经停止运行了");
        return SDBCommandResult::Continue;
    }
    for (std::size_t Index{0}; Index < Count && !NPCTrap::HasHalted(); ++Index)
    {
        SDBStepCycle(Context.GetTop());
        ++Context.GetCycles();
        if (GetGlobalWatchpointPool().CheckAll())
        {
            NPCTrap::Stop();
            std::println("程序因监视点变化而停止。");
            break;
        }
    }
    return SDBCommandResult::Continue;
}
