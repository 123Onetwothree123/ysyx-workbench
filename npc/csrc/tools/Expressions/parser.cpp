#include "parser.hpp"

#include "BinaryOpNode.hpp"
#include "DereferenceNode.hpp"
#include "NumberNode.hpp"
#include "ParenthesizedNode.hpp"
#include "RegisterNode.hpp"
#include "UnaryMinusNode.hpp"

#include <format>
#include <memory>
parser::parser(std::vector<token> InputTokens)
{
    tokens = std::move(InputTokens);
}
bool parser::IsAtEnd() const noexcept
{
    return Current().IsEndOfInput();
}
const token &parser::Current() const
{
    // 如果position已经到了tokens的末尾，就返回最后一个token
    if (position < tokens.size())
    {
        return tokens[position];
    }
    return tokens.back();
}
const token &parser::Peek(std::size_t offset) const
{
    if (position + offset < tokens.size())
    {
        return tokens[position + offset];
    }
    return tokens.back();
}
const token &parser::Advance() noexcept
{
    auto &Token{Current()};
    if (!Token.IsEndOfInput())
    {
        ++position;
    }
    return Token;
}
int parser::GetCurrentPrecedence() const noexcept
{
    return Current().GetPrecedence();
}
bool parser::IsCurrentRightAssociative() const noexcept
{
    return Current().IsRightAssociative();
}
std::expected<std::unique_ptr<AstNode>, std::string> parser::parse()
{
    auto expression{ParseExpression(0)};
    if (!expression)
    {
        return expression;
    }
    // 解析完了之后应该没有多余的token了，如果还有，那就是错的
    if (!Current().IsEndOfInput())
    {
        return std::unexpected(std::format("位置{}处意外的'{}'", Current().GetPosition(), Current().GetText()));
    }
    return expression;
}
std::expected<std::unique_ptr<AstNode>, std::string> parser::ParseExpression(int MinPrecedence)
{
    // 先解析左侧（一元或基本项）
    auto LeftResult{ParseUnary()};
    if (!LeftResult)
    {
        return LeftResult;
    }
    auto left{std::move(*LeftResult)};
    // 当遇到优先级>=MinPrecedence的二元运算符时，构建右子树
    while (true)
    {
        auto precedence{GetCurrentPrecedence()}; // 当前token的优先级
        if (precedence == 0 || precedence < MinPrecedence)
        {
            break;
        }
        token OperatorToken{Current()}; // 保存运算符Token（传给BinaryOpNode）
        Advance();                      // 用运算符
        // 二元运算符是左结合；一元运算已经在ParseUnary() 中处理。
        auto NextMinimum{precedence + 1};
        auto RightResult{ParseExpression(NextMinimum)};
        if (!RightResult)
        {
            return RightResult;
        }
        // 构建新的左子树
        left = std::make_unique<BinaryOpNode>(OperatorToken, std::move(left), std::move(*RightResult));
    }
    return left;
}
std::expected<std::unique_ptr<AstNode>, std::string> parser::ParseUnary()
{
    auto &Token{Current()};
    if (Token.IsMinus()) // 一元负号
    {
        Advance();
        auto operand{ParseUnary()}; // 就是因为负号的优先级很高，所以继续解析一元表达式
        if (!operand)
        {
            return operand;
        }
        // 构建一元负号节点
        return std::make_unique<UnaryMinusNode>(std::move(*operand));
    }
    if (Token.IsStar()) // 一元星号，表示解引用
    {
        Advance();
        auto address{ParseUnary()}; // 地址
        if (!address)
        {
            return address;
        }
        // 构建解引用节点，默认4字节
        return std::make_unique<DereferenceNode>(std::move(*address), 4);
    }
    if (Token.IsReadMemory8() || Token.IsReadMemory16() || Token.IsReadMemory32())
    {
        auto size{Token.IsReadMemory8() ? 1 : (Token.IsReadMemory16() ? 2 : 4)};
        Advance();
        if (!Current().IsLeftParen())
        {
            return std::unexpected(std::format("位置{}处read{}后缺少左括号", Current().GetPosition(), size * 8));
        }
        Advance(); // 跳过左括号
        auto address{ParseExpression(0)};
        if (!address)
        {
            return address;
        }
        if (!Current().IsRightParen())
        {
            return std::unexpected(std::format("位置{}处read{}缺少右括号", Current().GetPosition(), size * 8));
        }
        Advance(); // 跳过右括号
        // 构建解引用节点
        return std::make_unique<DereferenceNode>(std::move(*address), size);
    }
    return ParsePrimary();
}
std::expected<std::unique_ptr<AstNode>, std::string> parser::ParsePrimary()
{
    // 处理数字、寄存器和括号表达式
    auto &Token{Current()};
    if (Token.IsNumber())
    {
        Advance();
        return std::make_unique<NumberNode>(Token.GetValue());
    }
    if (Token.IsRegister())
    {
        Advance();
        return std::make_unique<RegisterNode>(std::string(Token.GetText()));
    }
    if (Token.IsLeftParen())
    {
        Advance();
        auto inner{ParseExpression(0)}; // inner是括号内的表达式
        if (!inner)
        {
            return inner;
        }
        if (!Current().IsRightParen())
        {
            return std::unexpected(std::format("位置{}处缺少右括号", Current().GetPosition()));
        }
        Advance();
        // 构建括号表达式节点
        return std::make_unique<ParenthesizedNode>(std::move(*inner));
    }
    // 其他情况都是错
    return std::unexpected(std::format("位置{}处意外的'{}'", Token.GetPosition(), Token.GetText()));
}
