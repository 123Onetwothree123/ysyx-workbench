#include "cCommand.hpp"
#include "../NPCTrap.hpp"
#include <print>
#include <verilated.h>
#include "../SDBCommandContext.hpp"
#include "WatchpointPool.hpp"
#include "../NPCEvaluationContext.hpp"

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
    auto &dut = context.GetDUT();
    NPCEvaluationContext EvaluationContext{dut};
    while (!Verilated::gotFinish() && !NPCTrap::HasHalted())
    {
        dut.step();
        if (dut->trap_valid)
        {
            NPCTrap::Halt(static_cast<std::uint32_t>(dut->trap_pc), 0);
            std::println("trap了");
            break;
        }
        if (GetGlobalWatchpointPool().CheckAll(EvaluationContext))
        {
            std::println("因为要检查监视点，所以程序现在先停止");
            break;
        }
    }
    return SDBCommandResult::Continue;
}