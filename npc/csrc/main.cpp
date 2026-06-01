#include <verilated.h>
#include <VRV32I.h>
#include <print>
#include <cstddef>
#include <iostream>
#include "CliOptions.hpp"
#include "ImageLoader.hpp"
#include "DUTControl.hpp"
#include "disasm.hpp"
int main(int argc, char const *argv[])
{
    Verilated::commandArgs(argc, argv);
    auto options{CliOptions::Parse(argc, argv)};
    if (!options)
    {
        std::println(std::cerr, "{0}", options.error());
        return 1;
    }
    const auto image_size{ImageLoader::LoadFromCli(*options)};
    if (!image_size)
    {
        std::println(std::cerr, "{0}", image_size.error());
        return 1;
    }
    init_disasm();
    DUTControl dut;
    auto CPUCycles{std::size_t{0}};
    dut.Reset();
    while (!Verilated::gotFinish())
    {
        dut.Step();
        CPUCycles++;
    }
    dut.Final();
    std::println("仿真结束，{} 个周期", CPUCycles);
    return 0;
}
