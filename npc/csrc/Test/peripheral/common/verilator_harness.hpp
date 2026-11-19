#ifndef PERIPHERAL_COMMON_VERILATOR_HARNESS_HPP
#define PERIPHERAL_COMMON_VERILATOR_HARNESS_HPP
#include "Vriscv32e_npc_SimTop.h"
#include <verilated.h>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <vector>

namespace npc_test {

inline void tick(Vriscv32e_npc_SimTop &dut)
{
    dut.clock = 0;
    dut.eval();
    dut.clock = 1;
    dut.eval();
}

inline bool load_image(int argc, char **argv, std::vector<std::uint8_t> &image)
{
    if (argc < 2)
    {
        std::fprintf(stderr, "用法: %s main.bin\n", argv[0]);
        return false;
    }
    Verilated::commandArgs(argc, argv);
    std::ifstream in(argv[1], std::ios::binary);
    if (!in)
    {
        std::fprintf(stderr, "无法打开镜像 %s\n", argv[1]);
        return false;
    }
    image.assign(std::istreambuf_iterator<char>(in), {});
    return true;
}

inline void reset_and_load(Vriscv32e_npc_SimTop &dut,
                           const std::vector<std::uint8_t> &image)
{
    dut.clock = 0;
    dut.reset = 1;
    dut.ram_init_valid = 0;
    dut.ram_init_index = 0;
    dut.ram_init_data = 0;
    dut.uart_rx_valid = 0;
    dut.uart_rx_data = 0;
    dut.debug_gpr_raddr = 0;

    for (int i = 0; i < 20; ++i)
    {
        tick(dut);
    }
    const std::size_t words = (image.size() + 3) / 4;
    for (std::size_t word = 0; word < words; ++word)
    {
        std::uint32_t value = 0;
        for (std::size_t byte = 0; byte < 4; ++byte)
        {
            const std::size_t index = word * 4 + byte;
            if (index < image.size())
            {
                value |= static_cast<std::uint32_t>(image[index]) << (8 * byte);
            }
        }
        dut.ram_init_index = static_cast<std::uint32_t>(word);
        dut.ram_init_data = value;
        dut.ram_init_valid = 1;
        tick(dut);
    }
    dut.ram_init_valid = 0;
    dut.reset = 0;
}

inline bool halt(Vriscv32e_npc_SimTop &dut, int &exit_code)
{
    if (!dut.trap_valid)
    {
        return false;
    }
    dut.debug_gpr_raddr = 10;
    dut.eval();
    const std::uint32_t code = dut.debug_gpr_rdata;
    std::printf("halt pc=%08x code=%08x\n", dut.trap_pc, code);
    exit_code = code == 0 ? 0 : 1;
    return true;
}

inline int timeout(Vriscv32e_npc_SimTop &dut)
{
    std::printf("timeout pc=%08x\n", dut.debug_arch_pc);
    return 1;
}

} // namespace npc_test

#endif
