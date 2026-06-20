export module npc.NPCTrap;
import std;

export class NPCTrap final
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
