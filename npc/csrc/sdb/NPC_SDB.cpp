#include "NPC_SDB.hpp"
#include "SDBDPI.hpp"
#include "command/SDBCommandRegistry.hpp"
#include <iostream>
#include <print>
#include <string>

#ifdef CONFIG_SDB
#include <readline/readline.h>
#include <readline/history.h>
#endif

// 全局状态，true就是表示NPC停止，是EBREAK_DPI-C来设置
extern bool npc_halted;

namespace
{
void step_cycle(VRV32E32Reg &top)
{
    top.clk = 0;
    top.eval();
    top.clk = 1;
    top.eval();
}
} // namespace

void sdb_main_loop(std::unique_ptr<VRV32E32Reg> &top, size_t &cycles, bool batch_mode)
{
    SDBDPISetTopScope(top->name(), top->modelName());
#ifdef CONFIG_SDB
    SDBCommandRegistry Commands{*top, cycles};
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
#else
    while (!Verilated::gotFinish() && !npc_halted)
    {
        step_cycle(*top);
        ++cycles;
    }
#endif
}
