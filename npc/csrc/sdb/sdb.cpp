module;
#include <verilated.h>
module npc.sdb.sdb;
import npc.readline;
import npc.sdb.SDBCommandRegistry;
import npc.sdb.SDBCommandResult;

SDBExitReason SDB::MainLoop(DUT &dut, bool batch_mode)
{
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
            std::println(std::cerr, "Verilator已通过$finish请求结束仿真");
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
            std::println(std::cerr, "Verilator已通过$finish请求结束仿真");
            return SDBExitReason::VerilatorFinish;
        }
        if (commandResult == SDBCommandResult::Quit)
        {
            std::println("退出");
            return SDBExitReason::UserQuit;
        }
    }
}
