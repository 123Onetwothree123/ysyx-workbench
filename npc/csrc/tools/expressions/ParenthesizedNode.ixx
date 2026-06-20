export module npc.expressions.ParenthesizedNode;
import std;
import npc.expressions.ASTNode;
import npc.sdb.EvaluationContext;

export class ParenthesizedNode : public ASTNode
{
private:
    std::unique_ptr<ASTNode> inner;
public:
    explicit ParenthesizedNode(std::unique_ptr<ASTNode> inner);
    [[nodiscard]] std::uint32_t Evaluate(const EvaluationContext &context) const override;
    [[nodiscard]] std::string ToString() const override;
};
