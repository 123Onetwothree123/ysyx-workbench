#ifndef PARENTHESIZED_NODE_HPP
#define PARENTHESIZED_NODE_HPP
#include "AstNode.hpp"
#include<memory>
class ParenthesizedNode : public AstNode
{
private:
    std::unique_ptr<AstNode> inner;
public:
    explicit ParenthesizedNode(std::unique_ptr<AstNode> inner);
    [[nodiscard]] std::uint32_t Evaluate(const EvaluationContext &context) const override;
    [[nodiscard]] std::string ToString() const override;
};
#endif