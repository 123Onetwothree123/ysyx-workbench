#ifndef UNARY_MINUS_NODE_HPP
#define UNARY_MINUS_NODE_HPP
#include "ASTNode.hpp"
#include <memory>
class UnaryMinusNode : public ASTNode
{
private:
    std::unique_ptr<ASTNode> child;
public:
    explicit UnaryMinusNode(std::unique_ptr<ASTNode> child);
    [[nodiscard]] std::uint32_t Evaluate(const EvaluationContext &context) const override;
    [[nodiscard]] std::string ToString() const override;
};
#endif
