module;
#include <verilated.h>
module npc.sdb.command.cCommand;
import npc.NPCTrap;
import npc.DUT;
#ifdef CONFIG_WATCHPOINT
import npc.sdb.NPCEvaluationContext;
import npc.sdb.command.WatchpointPool;
#endif

[[nodiscard]] std::string_view cCommand::name() const noexcept
{
    return "c";
}
[[nodiscard]] SDBCommandUsageList cCommand::usage() const noexcept
{
    static const SDBCommandUsage entries[]{
        {"", "继续运行直到结束"}
    };
    return entries;
}
SDBCommandResult cCommand::execute(SDBCommandContext &context, std::string_view args)
{
    if (!args.empty())
    {
        std::println("用法：c");
        return SDBCommandResult::Continue;
    }
    auto &dut{context.GetDUT()};
#ifdef CONFIG_WATCHPOINT
    NPCEvaluationContext EvaluationContext{dut};
#endif
    while (!NPCTrap::HasHalted())
    {
        // $finish 可能与本拍已寄存的 halt 脉冲同时可见。先结算 halt，
        // 再退出，否则正常的 GOOD/BAD trap 会被误报成未触发陷阱。
        if (Verilated::gotFinish())
        {
            dut.PollSimulationHalt();
            break;
        }
        dut.step();
        // A DiffTest mismatch raised by this step has priority over a
        // same-cycle simulation halt, otherwise a0 == 0 could turn BAD into
        // an apparent GOOD result.
        if (NPCTrap::HasHalted())
            break;
        if (dut.PollSimulationHalt())
            break;
#ifdef CONFIG_WATCHPOINT
        if ((dut->debug_commit || dut->debug_trap_valid) &&
            GetGlobalWatchpointPool().CheckAll(EvaluationContext))
        {
            std::println("因为要检查监视点，所以程序现在先停止");
            break;
        }
#endif
    }
    return SDBCommandResult::Continue;
}
