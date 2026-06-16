#ifndef AST_NODE_HPP
#define AST_NODE_HPP
#include "../../sdb/EvaluationContext.hpp"
#include <string>
#include <cstdint>
class ASTNode
{
public:
    virtual ~ASTNode() = default;
    [[nodiscard]] virtual std::uint32_t Evaluate(const EvaluationContext &context) const = 0;
    [[nodiscard]] virtual std::string ToString() const = 0;
};
#endif