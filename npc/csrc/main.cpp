#include <verilated.h>
#include "VysyxSoCFull.h"
import std;
import npc;

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
    std::uint64_t TotalSteps = 0;
    while (!Verilated::gotFinish() && !NPCTrap::HasHalted())
    {
        dut.step();
        if (++TotalSteps % 10000 == 0)
            std::println("[step={} pc=0x{:08x}]", TotalSteps,
                         static_cast<std::uint32_t>(dut->debug_pc));
        if (dut->trap_valid)
        {
            std::println("trap了");
            const auto halt_code{dut.ReadGPR(10)}; // x10 = a0
            NPCTrap::Halt(static_cast<std::uint32_t>(dut->trap_pc), halt_code ? *halt_code : 1u);
        }
    }
#endif
    dut.final();
    auto result = NPCTrap::PrintResult(dut.GetCycle());
#ifdef CONFIG_DIFFTEST
    if (result != 0)
    {
        DiftestFinalCheck(dut);
    }
#endif
#if defined(CONFIG_LOG_LEVEL) && CONFIG_LOG_LEVEL > 0
    log_close();
#endif
    return result;
}
