#ifndef EXPRESSIONS_HPP
#define EXPRESSIONS_HPP
#include <cstdint>
#include <expected>
#include <string>
#include "EvaluationContext.hpp"
class expressions
{
public:
    expressions() = default;
    [[nodiscard]] std::expected<std::uint32_t, std::string> evaluate(std::string_view expression, const EvaluationContext &context);
    [[nodiscard]] bool validate(std::string_view expression);
};
#endif