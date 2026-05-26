#ifndef EXPRESSIONS_HPP
#define EXPRESSIONS_HPP
#include "AstNode.hpp"
#include "EvaluationContext.hpp"
#include <cstdint>
#include <expected>
#include <memory>
#include <string>
#include <string_view>
class Expressions
{
public:
    Expressions() = default;
    // 解析为AST
    [[nodiscard]] std::expected<std::unique_ptr<AstNode>, std::string> Parse(std::string_view expression);
    // 一次性解析和求值
    [[nodiscard]] std::expected<std::uint32_t, std::string> Evaluate(std::string_view expression, const EvaluationContext &context);
    // 纯语法检查
    [[nodiscard]] bool Validate(std::string_view expression);
};
#endif
