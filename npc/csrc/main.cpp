#include <verilated.h>
#include <Vminirvcpu.h>
#include <print>
#include <iostream>
#include <cstdint>
#include <memory>
#include <filesystem>
#include "memory.h"

static bool npc_halted = false;
static uint32_t halt_pc{0};  // 记录停止的时候的PC
static uint32_t halt_ret{0}; // 返回码，0是good，1是bad

void clock_change(std::unique_ptr<Vminirvcpu> &top); // 妈的直接把拉clk封装然后缩到函数里面
void reset_dut(std::unique_ptr<Vminirvcpu> &top);    // 复位Device Under Test被测设计的

extern "C" void npc_ebreak(int pc, int code)
{
    npc_halted = true;
    halt_pc = static_cast<uint32_t>(pc);
    halt_ret = static_cast<uint32_t>(code);
}

void clock_change(std::unique_ptr<Vminirvcpu> &top)
{
    top->clk = 0;
    top->eval();
    top->clk = 1;
    top->eval();
}
void reset_dut(std::unique_ptr<Vminirvcpu> &top)
{
    top->clk = 0;
    top->rst = 1;
    top->eval();
    top->clk = 1;
    top->eval();
    top->clk = 0;
    top->rst = 0;
    top->eval();
}
int main(int argc, char const *argv[])
{
    if (argc < 2)
    {
        std::println(std::cerr, "缺少测试文件参数");
        return 1;
    }
    Verilated::commandArgs(argc, argv);
    std::filesystem::path FilePath{argv[1]};
    const auto FileSize{load_file(FilePath)};
    std::println("文件加载了: {}, size = {} bytes", FilePath.string(), FileSize);
    auto top{std::make_unique<Vminirvcpu>()}; // 管不了了复制修改以前代码，直接创建顶层的对象然后实例
    uint64_t cycles{0};                       // 统计总周期数的
    reset_dut(top);
    while (!Verilated::gotFinish() && !npc_halted)
    {
        clock_change(top);
        ++cycles;
    }
    top->final();
    if (npc_halted)
    {
        if (halt_ret == 0)
        {
            std::println("HIT GOOD TRAP at pc = 0x{:08x}, cycles = {}", halt_pc, cycles);
            return 0;
        }
        std::println(std::cerr, "HIT BAD TRAP at pc = 0x{:08x}, code = {}, cycles = {}", halt_pc, halt_ret, cycles);
        return 1;
    }
    return 0;
}
