#include <cstdint>
#include <cstdio>
#include <cstring>
#include <verilated.h>
#include "Memory/Memory.hpp"
#include "VRV32I.h"
#include "AXI/AXI.hpp"
#include "DUT.hpp"
#include "CLIOptions.hpp"
#include"ImageLoader.hpp"

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
    return 0;
}
