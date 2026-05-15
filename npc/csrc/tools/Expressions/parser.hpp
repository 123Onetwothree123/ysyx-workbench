#ifndef PARSER_HPP
#define PARSER_HPP
#include "token.hpp"
#include "ast.hpp"
#include <memory>
#include <string>
#include <vector>
#include <expected>
class parser
{
private:
    std::vector<token> tokens;
    std::size_t position{0};                                  // 当前解析到的token的位置
    [[nodiscard]] bool IsAtEnd() const noexcept;               // 解析完了
    [[nodiscard]] const token &Current() const;                // 当前token
    [[nodiscard]] const token &Peek(std::size_t offset) const; // 向前看offset个token
    const token &Advance() noexcept;                           // 消耗当前token并返回
    // 判断当前token是否为二元运算符，返回优先级，如果不是运算符就返回0
    [[nodiscard]] int GetCurrentPrecedence() const noexcept;
    [[nodiscard]] bool IsCurrentRightAssociative() const noexcept; // 当前运算符是否右结合
    // 递归下降解析
    std::expected<std::unique_ptr<AstNode>, std::string> ParseExpression(int MinPrecedence); // MinPrecedence是当前表达式允许的最小优先级
    std::expected<std::unique_ptr<AstNode>, std::string> ParseUnary();                       // 解析一元表达式
    std::expected<std::unique_ptr<AstNode>, std::string> ParsePrimary();                     // 解析原子表达式

public:
    explicit parser(std::vector<token> tokens);
    [[nodiscard]] std::expected<std::unique_ptr<AstNode>, std::string> parse();
};
#endif