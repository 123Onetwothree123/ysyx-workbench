export module npc.sdb.NPCEvaluationContext;
import std;
import npc.sdb.EvaluationContext;
import npc.DUT;

export class NPCEvaluationContext final : public EvaluationContext
{
private:
    DUT &dut;

public:
    NPCEvaluationContext(DUT &InputDUT);
    std::uint32_t ReadRegister(std::string_view name) const override;
    std::uint32_t ReadMemory(std::uint32_t address, std::size_t size) const override;
    std::uint32_t GetPC() const override;
};
