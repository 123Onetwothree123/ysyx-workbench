#include <verilated.h>
#include "VysyxSoCFull.h"
import std;
import npc.DUT;
import npc.CLIOptions;
import npc.ImageLoader;
import npc.NPCTrap;
import npc.ysyxSoC;
import npc.log;
#ifdef CONFIG_SDB
import npc.sdb.sdb;
#endif
#ifdef CONFIG_DIFFTEST
import npc.difftest.difftest;
#endif

int main(int argc, char const *argv[])
{
#if defined(CONFIG_LOG_LEVEL) && CONFIG_LOG_LEVEL > 0
    log_init();
#endif
    Verilated::commandArgs(argc, argv);
    DUT dut;
    auto options = CLIOptions::Parse(argc, argv);
    if (!options)
    {
        std::println(std::cerr, "{}", options.error());
        return 1;
    }
    auto load = ImageLoader::LoadFromCLI(*options);
    if (!load)
    {
#ifdef CONFIG_SDB
        std::println("未加载镜像文件，进入空 SDB");
#else
        std::println(std::cerr, "{}", load.error());
        return 1;
#endif
    }
#ifdef CONFIG_DIFFTEST
    if (load)
    {
        auto diffResult{DifftestInitialize(options->GetDiffFile(), *load)};
        if (!diffResult)
        {
            std::println(std::cerr, "DiffTest 初始化失败：{}", diffResult.error());
        }
    }
#endif
    dut.reset();
#ifdef CONFIG_SDB
    SDB::MainLoop(dut);
#else
    while (!Verilated::gotFinish() && !NPCTrap::HasHalted())
    {
        dut.step();
        if (dut->trap_valid)
        {
            std::println("trap了");
            NPCTrap::Halt(dut->trap_pc, 0);
        }
    }
#endif
    dut.final();
    auto result = NPCTrap::PrintResult(dut.GetCycle());
#if defined(CONFIG_LOG_LEVEL) && CONFIG_LOG_LEVEL > 0
    log_close();
#endif
    return result;
}
