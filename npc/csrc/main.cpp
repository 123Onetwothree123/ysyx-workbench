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
    while (!NPCTrap::HasHalted())
    {
        dut.step(axi);
        // 临时加的进度输出，后面要删掉的
        if (dut.GetCycle() % 100000000 == 0) {
            std::println(std::cout, "周期数：{}", dut.GetCycle());
            // 临时debug：看IFU/EXU/LSU状态
            auto ifu_st = (*dut).rootp->RV32I__DOT__ifu__DOT__state;
            auto exu_st = (*dut).rootp->RV32I__DOT__exu__DOT__state;
            auto lsu_st = (*dut).rootp->RV32I__DOT__lsu__DOT__state;
            printf("状态 IFU=%d EXU=%d LSU=%d\n", ifu_st, exu_st, lsu_st);
            fflush(stdout);
        }
    }
    dut.final();
    return NPCTrap::PrintResult(dut.GetCycle());
}
