#ifndef AST_NODE_HPP
#define AST_NODE_HPP
#include "token.hpp"
#include "EvaluationContext.hpp"
#include <string>
#include <cstdint>
class AstNode
{
public:
    virtual ~AstNode() = default;
    [[nodiscard]] virtual std::uint32_t Evaluate(const EvaluationContext &context) const = 0;
    [[nodiscard]] virtual std::string ToString() const = 0;
};
#endif