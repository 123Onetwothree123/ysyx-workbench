#ifndef REGISTER_NODE_HPP
#define REGISTER_NODE_HPP
#include "AstNode.hpp"
class RegisterNode : public AstNode
{
private:
    std::string name;

public:
    explicit RegisterNode(std::string name);
    [[nodiscard]] std::uint32_t Evaluate(const EvaluationContext &context) const override;
    [[nodiscard]] std::string ToString() const override;
};
#endif