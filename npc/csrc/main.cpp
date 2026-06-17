#include <iostream>
#include <verilated.h>
#include <print>
#include "DUT.hpp"
#include "CLIOptions.hpp"
#include "ImageLoader.hpp"
#include "NPCTrap.hpp"
#include "ysyxSoC/ysyxSoC.hpp"
#ifdef CONFIG_SDB
#include "sdb/sdb.hpp"
#endif

int main(int argc, char const *argv[])
{
    // 才发现删过头了，忘记写这行代码了
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
    return NPCTrap::PrintResult(dut.GetCycle());
}
