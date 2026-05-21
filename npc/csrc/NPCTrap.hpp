#ifndef NPC_TRAP_HPP
#define NPC_TRAP_HPP
#include <cstddef>
#include <cstdint>
class NPCTrap final
{
public:
    NPCTrap() = delete;
    static void Halt(std::uint32_t PC, std::uint32_t Code) noexcept;
    static void Stop() noexcept;
    [[nodiscard]] static bool HasHalted() noexcept;
    [[nodiscard]] static std::uint32_t GetPC() noexcept;
    [[nodiscard]] static std::uint32_t GetCode() noexcept;
    [[nodiscard]] static int PrintResult(std::size_t Cycles);
};
extern "C" void npc_ebreak(int pc, int code);
#endif
