export module npc.trace.ReadelfFunction;
import std;

export struct ReadelfFunction
{
    std::string_view name{};
    std::size_t start{0};
    std::size_t end{0};
    [[nodiscard]] std::size_t size() const noexcept;
    [[nodiscard]] bool contains(std::size_t address) const noexcept;
};
