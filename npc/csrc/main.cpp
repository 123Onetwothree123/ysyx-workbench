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
    uint64_t s = 0; uint32_t last_pc = 0;
    while (!Verilated::gotFinish() && !NPCTrap::HasHalted())
    {
        dut.step(); s++;
        auto pc = static_cast<uint32_t>(dut->debug_pc);
        if (pc != last_pc) {
            last_pc = pc;
            if (pc == 0x300000c8 || pc == 0x300000d0) {
                auto a0 = dut.ReadGPR(10);
                auto t0 = dut.ReadGPR(5);
                auto t1 = dut.ReadGPR(6);
                std::println("[{}]pc=0x{:08x} a0=0x{:08x} t0=0x{:08x} t1=0x{:08x}", s, pc, a0.value_or(0), t0.value_or(0), t1.value_or(0));
            }
        }
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
