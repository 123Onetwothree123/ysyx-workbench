#include <verilated.h>
#include <VRV32E32Reg.h>
#include <print>
#include <cstddef>
#include <iostream>
#include "CliOptions.hpp"
#include "difftest.hpp"
#include "DUTControl.hpp"
#include "ImageLoader.hpp"
#include "NPCTrap.hpp"
#include "NPC_SDB.hpp"
#include "SDBDPI.hpp"
#include "TraceInitializer.hpp"
#include "trace.hpp"
int main(int argc, char const *argv[])
{
    // 为节省PPT空间，部分代码讲解，写入注释中
    auto options{CliOptions::Parse(argc, argv)}; // 第一步，先解析
    if (!options)
    {
        std::println(std::cerr, "{}", options.error());
        return 1;
    }
    Verilated::commandArgs(argc, argv);
    const auto image_size{ImageLoader::LoadFromCli(*options)}; // 加载镜像
    if (!image_size)
    {
        std::println(std::cerr, "{}", image_size.error());
        return 1;
    }
    auto trace_init{TraceInitializer::InitFromCli(*options)};
    if (!trace_init)
    {
        std::println(std::cerr, "{}", trace_init.error());
        return 1;
    }
    init_disasm();
    DUTControl dut;              // 建立DUT和刚好利用raii来管理对象，然后DUTC里面封装好了verilator生成的处理器对象
    auto cycles{std::size_t{0}}; // 统计总周期数的
    dut.Reset();
    SDBDPISetTopScope(dut.GetTop().name(), dut.GetTop().modelName()); // 做DPI作用域
    auto difftest_init{DifftestInitialize(options->GetDiffRefSo(), image_size.value())};
    if (!difftest_init)
    {
        std::println(std::cerr, "{}", difftest_init.error());
        return 1;
    }
    sdb_main_loop(dut.GetTop(), cycles, options->IsBatchMode());
    // 最后再给verilator的对象清理和打印结果
    dut.Final();
    if (NPCTrap::HasHalted()) // 看有没有ebreak指令
    {
        return NPCTrap::PrintResult(cycles);
    }
    return 0;
}
