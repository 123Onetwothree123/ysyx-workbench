module;
#include <verilated.h>
module npc.sdb.command.cCommand;
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
    static_cast<void>(args);
    auto &dut{context.GetDUT()};
    NPCEvaluationContext EvaluationContext{dut};
    while (!Verilated::gotFinish() && !NPCTrap::HasHalted())
    {
        dut.step();
        // A DiffTest mismatch raised by this step has priority over a
        // same-cycle simulation halt, otherwise a0 == 0 could turn BAD into
        // an apparent GOOD result.
        if (NPCTrap::HasHalted())
            break;
        if (dut->trap_valid)
        {
            const auto halt_code{dut.ReadGPR(10)}; // x10 = a0
            NPCTrap::Halt(static_cast<std::uint32_t>(dut->trap_pc), halt_code ? *halt_code : 1u);
            std::println("收到仿真 halt 请求");
            break;
        }
        if ((dut->debug_commit || dut->debug_trap_valid) &&
            GetGlobalWatchpointPool().CheckAll(EvaluationContext))
        {
            std::println("因为要检查监视点，所以程序现在先停止");
            break;
        }
    }
    return SDBCommandResult::Continue;
}
