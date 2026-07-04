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
    auto CharTests{std::filesystem::path{"csrc/Test/char-test/build/char-test-riscv32-npc.bin"}};
    if (std::filesystem::exists(CharTests))
    {
        auto size{std::filesystem::file_size(CharTests)};
        FlashMemory.resize(size);
        std::ifstream ifs{CharTests.string(), std::ios::binary};
        ifs.read(reinterpret_cast<char *>(FlashMemory.data()), size);
    }
    else
    {
        // 初始化FlashMemory，模拟烧录了数据FlashMemory[i] = i
        FlashMemory.resize(256);
        for (std::size_t i = 0; i < FlashMemory.size(); i++)
        {
            FlashMemory[i] = static_cast<std::uint8_t>(i);
        }
    }
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
