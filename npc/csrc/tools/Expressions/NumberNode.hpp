#ifndef NUMBER_NODE_HPP
#define NUMBER_NODE_HPP
#include "AstNode.hpp"
class NumberNode : public AstNode
{
private:
    std::uint32_t value;

public:
    explicit NumberNode(std::uint32_t value);
    [[nodiscard]] std::uint32_t Evaluate(const EvaluationContext &context) const override;
    [[nodiscard]] std::string ToString() const override;
};
#endif