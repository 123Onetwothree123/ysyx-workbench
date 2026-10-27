module;
#include <verilated.h>
module npc.sdb.command.cCommand;
import npc.config;
import npc.NPCTrap;
import npc.DUT;
import npc.sdb.NPCEvaluationContext;
import npc.sdb.command.WatchpointPool;

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
    NPCEvaluationContext EvaluationContext{dut};
    // 运行期间把 stdin 非阻塞转发给 direct-NPC UART；SDB 命令读取发生
    // 在 c 返回之后，因此不会与客户机输入冲突。
    dut.EnableUARTStdin(true);
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
        if constexpr (npc::HasWatchpoint)
        {
            if ((dut->debug_commit || dut->debug_trap_valid) &&
                GetGlobalWatchpointPool().CheckAll(EvaluationContext))
            {
                std::println("因为要检查监视点，所以程序现在先停止");
                break;
            }
        }
    }
    dut.EnableUARTStdin(false);
    return SDBCommandResult::Continue;
}
