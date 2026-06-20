export module npc.expressions.UnaryMinusNode;
import std;
import npc.expressions.ASTNode;
import npc.sdb.EvaluationContext;

export class UnaryMinusNode : public ASTNode
{
private:
    std::unique_ptr<ASTNode> child;
public:
    explicit UnaryMinusNode(std::unique_ptr<ASTNode> child);
    [[nodiscard]] std::uint32_t Evaluate(const EvaluationContext &context) const override;
    [[nodiscard]] std::string ToString() const override;
};
