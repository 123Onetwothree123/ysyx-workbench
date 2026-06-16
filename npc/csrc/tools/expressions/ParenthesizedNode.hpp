#ifndef PARENTHESIZED_NODE_HPP
#define PARENTHESIZED_NODE_HPP
#include "ASTNode.hpp"
#include<memory>
class ParenthesizedNode : public ASTNode
{
private:
    std::unique_ptr<ASTNode> inner;
public:
    explicit ParenthesizedNode(std::unique_ptr<ASTNode> inner);
    [[nodiscard]] std::uint32_t Evaluate(const EvaluationContext &context) const override;
    [[nodiscard]] std::string ToString() const override;
};
#endif