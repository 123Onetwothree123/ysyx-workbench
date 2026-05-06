#ifndef EXPRESSION_ERROR_HPP
#define EXPRESSION_ERROR_HPP
#include <expected>
#include <string>
class ExpressionError final : public std::exception
{
public:
    explicit ExpressionError(std::string InputMessage);
    [[nodiscard]] const char *what() const noexcept override;

private:
    std::string message;
};
#endif