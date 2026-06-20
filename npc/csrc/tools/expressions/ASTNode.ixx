export module npc.expressions.ASTNode;
import std;
import npc.sdb.EvaluationContext;

export class ASTNode
{
public:
    virtual ~ASTNode() = default;
    [[nodiscard]] virtual std::uint32_t Evaluate(const EvaluationContext &context) const = 0;
    [[nodiscard]] virtual std::string ToString() const = 0;
};
