module;
#include <verilated.h>
module npc.sdb.sdb;
import npc.Output;
import npc.DUT;
import npc.readline;
import npc.sdb.SDBCommandRegistry;
import npc.sdb.SDBCommandResult;

SDBExitReason SDB::MainLoop(DUT &dut, bool batch_mode)
{
    auto &out{dut.Context().Out()};
    SDBCommandRegistry Commands{dut};
    if (batch_mode)
    {
        Commands.Execute("c");
        return Verilated::gotFinish()
                   ? SDBExitReason::VerilatorFinish
                   : SDBExitReason::BatchComplete;
    }

    while (true)
    {
        // Check before readline as well as after a command.  This gives
        // $finish priority over an already-buffered `q` and over stdin EOF.
        if (Verilated::gotFinish())
        {
            out.Error("Verilator已通过$finish请求结束仿真");
            return SDBExitReason::VerilatorFinish;
        }

        auto line{readline("(npc) ")};
        if (line == nullptr)
            return SDBExitReason::EndOfInput;

        if (line[0] != '\0')
        {
            add_history(line);
        }
        auto cmd{std::string{line}};
        free(line);
        Commands.AddHistory(cmd);
        const auto commandResult{Commands.Execute(cmd)};
        if (Verilated::gotFinish())
        {
            out.Error("Verilator已通过$finish请求结束仿真");
            return SDBExitReason::VerilatorFinish;
        }
        if (commandResult == SDBCommandResult::Quit)
        {
            out.Info("退出");
            return SDBExitReason::UserQuit;
        }
    }
}
