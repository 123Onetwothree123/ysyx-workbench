#include <iostream>
#include <verilated.h>
#include "DUT.hpp"
#include "CLIOptions.hpp"
#include "ImageLoader.hpp"
#include "NPCTrap.hpp"
#include "ysyxSoC/ysyxSoC.hpp"
int main(int argc, char const *argv[])
{
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
        std::println(std::cerr, "{}", load.error());
        return 1;
    }
    dut.reset();
    while (!NPCTrap::HasHalted())
    {
        dut.step();
        if (dut->trap_valid)
        {
            NPCTrap::Halt(dut->trap_pc, 0);
        }
    }
    dut.final();
    return NPCTrap::PrintResult(dut.GetCycle());
}
