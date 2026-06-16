#ifndef REGISTER_NODE_HPP
#define REGISTER_NODE_HPP
#include "ASTNode.hpp"
class RegisterNode : public ASTNode
{
private:
    std::string name;
public:
    explicit RegisterNode(std::string name);
    [[nodiscard]] std::uint32_t Evaluate(const EvaluationContext &context) const override;
    [[nodiscard]] std::string ToString() const override;
};
#endif