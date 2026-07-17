#include <print>
#include <cstdint>
#include <filesystem>
#include "ImmGen.hpp"
#include "Decoder.hpp"
#include "minirvEMU.hpp"
#include "ProgramLoader.hpp"
#include <am.hpp>
#include <klib.hpp>

int main(int argc, char const *argv[])
{
    if (argc < 2)
    {
        std::println("Usage: {} <filename.bin>", argv[0]);
        return -1;
    }
    ioe_init();
    minirvEMU emu{};
    std::filesystem::path binPath{argv[1]};
    auto result{ProgramLoader::LoadBinary(binPath)};
    if (!result.has_value())
    {
        printf("load binary fail\n");
        return -1;
    }
    const auto &code{result.value()};
    for (std::size_t i{0}; i < code.size(); ++i)
        emu.write_byte(static_cast<std::uint32_t>(i), code[i]);
    std::println("Loaded {} bytes from {}", code.size(), binPath.filename().string());
    while (!emu.IsHalted())
        emu.step();
    emu.UpdateVGA();
    printf("Simulation halted.\n");
    emu.PrintState();
    return 0;
}
