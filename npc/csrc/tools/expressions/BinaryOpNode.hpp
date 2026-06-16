#ifndef BINARY_OP_NODE_HPP
#define BINARY_OP_NODE_HPP
#include "ASTNode.hpp"
#include "token.hpp"
#include <memory>
class BinaryOpNode : public ASTNode
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
#endif
