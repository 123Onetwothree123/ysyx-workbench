#ifndef NPC_EVALUATION_CONTEXT_HPP
#define NPC_EVALUATION_CONTEXT_HPP
#include "tools/Expressions/EvaluationContext.hpp"
#include <cstdint>
#include <string_view>
class NPCEvaluationContext : public EvaluationContext
{
public:
    [[nodiscard]] std::uint32_t ReadRegister(std::string_view Name) const override;
    [[nodiscard]] std::uint32_t ReadMemory(std::uint32_t Address, std::size_t Size) const override;
    [[nodiscard]] std::uint32_t GetProgramCounter() const override;
};
#endif
