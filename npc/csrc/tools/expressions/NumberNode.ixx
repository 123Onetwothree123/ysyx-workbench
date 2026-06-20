export module npc.expressions.NumberNode;
import std;
import npc.expressions.ASTNode;
import npc.sdb.EvaluationContext;

export class NumberNode : public ASTNode
{
private:
    std::uint32_t value;
public:
    explicit NumberNode(std::uint32_t value);
    [[nodiscard]] std::uint32_t Evaluate(const EvaluationContext &context) const override;
    [[nodiscard]] std::string ToString() const override;
};
