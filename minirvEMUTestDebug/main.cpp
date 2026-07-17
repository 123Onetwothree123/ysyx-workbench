#include <cstdio>
import std;
import minirvemu.emu;
import minirvemu.ProgramLoader;

int main(int argc, char const *argv[])
{
    if (argc < 2)
    {
        std::println("Usage: {} <filename.bin> [trace_output.csv]", argv[0]);
        return -1;
    }
    minirvEMU emu{};
    std::filesystem::path binPath{argv[1]};
    std::filesystem::path tracePath{(argc >= 3) ? std::filesystem::path{argv[2]} : std::filesystem::path{"runtime_trace.csv"}};
    if (!emu.EnableTrace(tracePath))
    {
        std::println(stderr, "open trace file fail: {}", tracePath.string());
        return -1;
    }
    auto result{ProgramLoader::LoadBinary(binPath)};
    if (!result.has_value())
    {
        std::println(stderr, "load binary fail");
        return -1;
    }
    const auto &code{result.value()};
    for (std::size_t i{0}; i < code.size(); ++i)
        emu.write_byte(static_cast<std::uint32_t>(i), code[i]);
    std::println("Loaded {} bytes from {}", code.size(), binPath.filename().string());
    std::println("Trace output: {}", tracePath.string());
    while (!emu.IsHalted())
        emu.step();
    emu.UpdateVGA();
    std::println("Simulation halted.");
    emu.PrintState();
    return 0;
}
