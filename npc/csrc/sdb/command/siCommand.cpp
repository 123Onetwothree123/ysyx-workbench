module;
#include <verilated.h>
module npc.sdb.command.siCommand;
import npc.NPCTrap;
import npc.DUT;
import npc.sdb.SDBCommandUtils;
import npc.sdb.NPCEvaluationContext;
import npc.sdb.command.WatchpointPool;

[[nodiscard]] std::string_view siCommand::name() const noexcept
{
    return "si";
}
[[nodiscard]] SDBCommandUsageList siCommand::usage() const noexcept
{
    static const SDBCommandUsage entries[]{
        {"[N]", "单步执行 N 条指令，默认 1 条"},
    };
    return entries;
}
SDBCommandResult siCommand::execute(SDBCommandContext &context, std::string_view args)
{
    auto count{std::size_t{1}};
    args = SDBTrimLeft(args);
    if (!args.empty())
    {
        // 用有符号类型接收，这样能检测负数
        // auto parsed{std::ptrdiff_t{0}};
        auto parsed{static_cast<std::ptrdiff_t>(0)};
        const auto result{std::from_chars(args.data(), args.data() + args.size(), parsed, 10)};
        if (result.ec != std::errc())
        {
            std::println("参数{0}用from_chars解析失败了", args);
            return SDBCommandResult::Continue;
        }
        if (parsed <= 0)
        {
            std::println("步数必须得是正整数，结果现在得到的是 {}", parsed);
            return SDBCommandResult::Continue;
        }
        auto remainder{std::string_view{result.ptr, static_cast<std::size_t>(args.data() + args.size() - result.ptr)}};
        remainder = SDBTrimLeft(remainder);
        if (!remainder.empty())
        {
            std::println("参数尾部有多余的内容'{}'", remainder);
            return SDBCommandResult::Continue;
        }
        count = static_cast<std::size_t>(parsed);
    }
    if (NPCTrap::HasHalted())
    {
        std::println("NPC已经停止运行了");
        return SDBCommandResult::Continue;
    }
    auto &dut{context.GetDUT()};
    NPCEvaluationContext EvaluationContext{dut};
    for (std::size_t index{0}; index < count && !NPCTrap::HasHalted(); ++index)
    {
        dut.step();
        if (dut->trap_valid)
        {
            const auto halt_code{dut.ReadGPR(10)}; // x10 = a0
            NPCTrap::Halt(static_cast<std::uint32_t>(dut->trap_pc), halt_code ? *halt_code : 1u);
            std::println("trap了");
            break;
        }
        if (GetGlobalWatchpointPool().CheckAll(EvaluationContext))
        {
            std::println("因为监视点变化，程序停止");
            break;
        }
    }
    return SDBCommandResult::Continue;
}
