#include "NPC_SDB.hpp"
#include "SDBDPI.hpp"
#include "command/SDBCommandRegistry.hpp"
#include "command/SDBCommandUtils.hpp"
#include <iostream>
#include <print>
#include <string>

#ifdef CONFIG_SDB
#include <readline/readline.h>
#include <readline/history.h>
#endif

// 全局状态，true就是表示NPC停止，是EBREAK_DPI-C来设置
extern bool npc_halted;

void sdb_main_loop(std::unique_ptr<VRV32E32Reg> &top, std::size_t &cycles, bool batch_mode)
{
    SDBDPISetTopScope(top->name(), top->modelName()); // 先设置作用域
#ifdef CONFIG_SDB
    SDBCommandRegistry Commands{*top, cycles};
    if (batch_mode)
    {
        Commands.Execute("c");
        return;
    }
    auto line{readline("(npc) ")};//唉，到最后还是用了readline
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
#else
    while (!Verilated::gotFinish() && !npc_halted)
    {
        SDBStepCycle(*top);
        ++cycles;
    }
#endif
}
