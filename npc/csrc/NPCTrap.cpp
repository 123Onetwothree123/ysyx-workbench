#include "NPCTrap.hpp"
#include "trace/itrace.hpp"
#include <iostream>
#include <print>
namespace
{
    bool Halted{false};
    std::uint32_t HaltPC{0};   // 记录停止的时候的PC
    std::uint32_t HaltCode{0}; // 返回码，0是good，1是bad
} // namespace
void NPCTrap::Halt(std::uint32_t PC, std::uint32_t Code) noexcept
{
    Halted = true;
    HaltPC = PC;
    HaltCode = Code;
}
void NPCTrap::Stop() noexcept
{
    Halted = true;
}
bool NPCTrap::HasHalted() noexcept
{
    return Halted;
}
std::uint32_t NPCTrap::GetPC() noexcept
{
    return HaltPC;
}
std::uint32_t NPCTrap::GetCode() noexcept
{
    return HaltCode;
}
int NPCTrap::PrintResult(std::size_t Cycles)
{
    if (HaltCode == 0)
    {
        std::println("HIT GOOD TRAP at pc = 0x{0:08x}, cycles = {1}", HaltPC, Cycles);
        return 0;
    }
    PrintIringbuf(HaltPC);
    std::println(std::cerr, "HIT BAD TRAP at pc = 0x{0:08x}, code = {1}, cycles = {2}", HaltPC, HaltCode, Cycles);
    return 1;
}
extern "C" void npc_ebreak(int pc, int code)
{
    NPCTrap::Halt(static_cast<std::uint32_t>(pc), static_cast<std::uint32_t>(code));
}
