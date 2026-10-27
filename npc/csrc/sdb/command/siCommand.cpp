module;
#include <verilated.h>
module npc.sdb.command.siCommand;
import npc.config;
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
    std::size_t completedBoundaries{0};
    while (completedBoundaries < count && !NPCTrap::HasHalted())
    {
        if (Verilated::gotFinish())
            break;
        dut.step();
        if (NPCTrap::HasHalted())
            break;
        if (dut.PollSimulationHalt())
            break;
        // si 的计数单位是架构边界，不是时钟周期。cache miss、
        // MDU 和 AXI stall 可以经历任意多拍，都不应提前结束单步。
        // 例外指令不会 debug_commit，但 trap 提交同样是一个精确的
        // 架构边界。同拍的退休和 trap 只计一次，因为 C++ 无法在
        // 一次 dut.step() 中间停下来。
        const bool trapBoundary{static_cast<bool>(dut->debug_trap_valid)};
        if (dut->debug_commit || trapBoundary)
            ++completedBoundaries;

        // 监视点只在架构状态边界重新求值，避免把流水线
        // 中间周期误当成一条指令。
        if constexpr (npc::HasWatchpoint)
        {
            if ((dut->debug_commit || trapBoundary) &&
                GetGlobalWatchpointPool().CheckAll(EvaluationContext))
            {
                std::println("因为监视点变化，程序停止");
                break;
            }
        }
        // 例外是调试器必须暴露的停止原因。即使用户请求
        // si N (N > 1)，也不能在未通知用户的情况下继续执行 handler。
        if (trapBoundary)
        {
            std::println(
                "遇到异常，停止单步（pc=0x{:x}, cause=0x{:08x}, target=0x{:x}）",
                dut.ReadTrapPC(),
                dut.ReadTrapCause(),
                dut.ReadTrapTarget());
            break;
        }
    }
    if (Verilated::gotFinish() && !NPCTrap::HasHalted())
    {
        // 先结算与 $finish 同拍或上一拍已寄存的 halt，只有确实没有
        // custom halt 时才报告“请求结束但未触发陷阱”。
        if (!dut.PollSimulationHalt())
        {
            std::println(
                "Verilator已请求结束，停止单步（本次完成 {} / {} 个架构边界）",
                completedBoundaries,
                count);
        }
    }
    return SDBCommandResult::Continue;
}
