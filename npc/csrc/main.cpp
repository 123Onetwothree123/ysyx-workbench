#include <cstdint>
#include <cstdio>
#include <cstring>
#include <iostream>
#include <verilated.h>
#include "Memory/Memory.hpp"
#include "VRV32I.h"
#include "AXI/AXI.hpp"
#include "DUT.hpp"
#include "CLIOptions.hpp"
#include "ImageLoader.hpp"
#include "NPCTrap.hpp"

int main(int argc, char const *argv[])
{
    Memory memory;
    DUT dut;
    AXI axi(memory);
    auto options = CLIOptions::Parse(argc, argv);
    if (!options)
    {
        std::println(std::cerr, "{}", options.error());
        return 1;
    }
    auto load = ImageLoader::LoadFromCLI(*options, memory);
    if (!load)
    {
        std::println(std::cerr, "{}", load.error());
        return 1;
    }
    dut.reset();
    axi.reset(*dut);
    while (!NPCTrap::HasHalted() && dut.GetCycle() < 200000000)
    {
        dut.step(axi);
        if (dut->io_TrapValid)
        {
            NPCTrap::Halt(dut->io_TrapPC, dut->io_TrapCode);
        }
    }
    dut.final();
    return NPCTrap::PrintResult(dut.GetCycle());
}
