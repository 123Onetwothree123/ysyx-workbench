module;
module npc.sdb.command.cCommand;
import npc.Output;
import npc.config;
import npc.NPCTrap;
import npc.DUT;
import npc.sdb.NPCEvaluationContext;
import npc.sdb.command.WatchpointPool;
import npc.sdb.SDBCommandUtils;
import npc.SimulationContext;

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
    auto &out{context.GetSimulation().Out()};
    if (SDBRejectUnexpectedArguments(args, "用法：c", out))
    {
        return SDBCommandResult::Continue;
    }
    auto &dut{context.GetDUT()};
    NPCEvaluationContext EvaluationContext{dut};
    // 运行期间把 stdin 非阻塞转发给 direct-NPC UART；SDB 命令读取发生
    // 在 c 返回之后，因此不会与客户机输入冲突。
    dut.EnableUARTStdin(true);
    while (!NPCTrap::HasHalted())
    {
        if (dut.StepWithHaltPriority())
            break;
        if constexpr (npc::HasWatchpoint)
        {
            if ((dut->debug_commit || dut->debug_trap_valid) &&
                context.GetSimulation().Watchpoints().CheckAll(EvaluationContext))
            {
                out.Info("因为要检查监视点，所以程序现在先停止");
                break;
            }
        }
    }
    dut.EnableUARTStdin(false);
    return SDBCommandResult::Continue;
}
