export module npc.expressions.BinaryOpNode;
import std;
import npc.expressions.ASTNode;
import npc.expressions.token;
import npc.sdb.EvaluationContext;

export class BinaryOpNode : public ASTNode
{
private:
    token Token;
    std::unique_ptr<ASTNode> Left;
    std::unique_ptr<ASTNode> Right;
public:
    BinaryOpNode(token Token, std::unique_ptr<ASTNode> Left, std::unique_ptr<ASTNode> Right);
    [[nodiscard]] std::uint32_t Evaluate(const EvaluationContext &context) const override;
    [[nodiscard]] std::string ToString() const override;
};
