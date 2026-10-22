export module npc.expressions.RegisterNode;
import std;
import npc.expressions.ASTNode;
import npc.sdb.EvaluationContext;

export class RegisterNode : public ASTNode
{
private:
    std::string name;
public:
    explicit RegisterNode(std::string name);
    [[nodiscard]] std::uint64_t Evaluate(const EvaluationContext &context) const override;
    [[nodiscard]] std::string ToString() const override;
};
