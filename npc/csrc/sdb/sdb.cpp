module npc.sdb.sdb;
import npc.readline;
import npc.sdb.SDBCommandRegistry;
import npc.sdb.SDBCommandResult;
import npc.NPCTrap;

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
