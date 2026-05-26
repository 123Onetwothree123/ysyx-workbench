#ifndef UNARY_MINUS_NODE_HPP
#define UNARY_MINUS_NODE_HPP
#include "AstNode.hpp"
#include <memory>
class UnaryMinusNode : public AstNode
{
private:
    std::unique_ptr<AstNode> child;
public:
    explicit UnaryMinusNode(std::unique_ptr<AstNode> child);
    [[nodiscard]] std::uint32_t Evaluate(const EvaluationContext &context) const override;
    [[nodiscard]] std::string ToString() const override;
};
#endif
