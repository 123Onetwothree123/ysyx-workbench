module;
#include <cstdio>
#include <readline/readline.h>
#include <readline/history.h>
module npc.sdb.sdb;
import npc.sdb.SDBCommandRegistry;

void SDB::MainLoop(DUT &dut, bool batch_mode)
{
    SDBCommandRegistry Commands{dut};
    if (batch_mode)
    {
        Commands.Execute("c");
        return;
    }
    auto line{readline("(npc) ")};
    while (line != nullptr)
    {
        if (line[0] != '\0')
        {
            add_history(line);
        }
        auto cmd{std::string{line}};
        free(line);
        Commands.AddHistory(cmd);
        if (Commands.Execute(cmd) == SDBCommandResult::Quit)
        {
            std::println("退出");
            break;
        }
        line = readline("(npc) ");
    }
}
