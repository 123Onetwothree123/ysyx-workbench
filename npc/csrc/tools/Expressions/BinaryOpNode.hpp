#ifndef BINARY_OP_NODE_HPP
#define BINARY_OP_NODE_HPP
#include "AstNode.hpp"
#include "token.hpp"
#include <memory>
class BinaryOpNode : public AstNode
{
private:
    token Token;
    std::unique_ptr<AstNode> Left;
    std::unique_ptr<AstNode> Right;

public:
    BinaryOpNode(token Token, std::unique_ptr<AstNode> Left, std::unique_ptr<AstNode> Right);
    [[nodiscard]] std::uint32_t Evaluate(const EvaluationContext &context) const override;
    [[nodiscard]] std::string ToString() const override;
};
#endif
