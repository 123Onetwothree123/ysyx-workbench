#ifndef NPC_EVALUATION_CONTEXT_HPP
#define NPC_EVALUATION_CONTEXT_HPP
#include "EvaluationContext.hpp"
#include "DUT.hpp"
class NPCEvaluationContext final : public EvaluationContext
{
private:
    DUT &dut;

public:
    NPCEvaluationContext(DUT &InputDUT);
    std::uint32_t ReadRegister(std::string_view name) const override;
    std::uint32_t ReadMemory(std::uint32_t address, std::size_t size) const override;
    std::uint32_t GetPC() const override;
};
#endif