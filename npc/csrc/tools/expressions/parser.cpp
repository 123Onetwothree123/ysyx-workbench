module npc.expressions.parser;
import npc.expressions.BinaryOpNode;
import npc.expressions.DereferenceNode;
import npc.expressions.NumberNode;
import npc.expressions.ParenthesizedNode;
import npc.expressions.RegisterNode;
import npc.expressions.UnaryMinusNode;
namespace
{
struct DepthGuard
{
    std::size_t &depth;
    explicit DepthGuard(std::size_t &value) : depth(value)
    {
        ++depth;
    }
    ~DepthGuard()
    {
        --depth;
    }
};
} // namespace

parser::parser(std::vector<token> InputTokens)
{
    tokens = std::move(InputTokens);
    // Current()/Peek() 取 back()，须保证有 End token。
    if (tokens.empty())
    {
        tokens.push_back(token::MakeEnd(0));
    }
}
const token &parser::Current() const
{
    if (position < tokens.size())
    {
        return tokens[position];
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
std::expected<std::unique_ptr<ASTNode>, std::string> parser::parse()
{
    auto expression{ParseExpression(0)};
    if (!expression)
    {
        return expression;
    }
    if (!Current().IsEndOfInput())
    {
        return std::unexpected(std::format("位置{0}处意外的'{1}'", Current().GetPosition(), Current().GetText()));
    }
    return expression;
}
std::expected<std::unique_ptr<ASTNode>, std::string> parser::ParseExpression(int MinPrecedence)
{
    if (depth >= MaxDepth)
    {
        return std::unexpected{"表达式嵌套过深"};
    }
    DepthGuard guard{depth};
    auto LeftResult{ParseUnary()};
    if (!LeftResult)
    {
        return LeftResult;
    }
    auto left{std::move(*LeftResult)};
    // 优先级 >= MinPrecedence 时构建右子树。
    while (true)
    {
        auto precedence{GetCurrentPrecedence()};
        if (precedence == 0 || precedence < MinPrecedence)
        {
            break;
        }
        token OperatorToken{Current()};
        Advance();
        // 二元运算符左结合；一元已在 ParseUnary 处理。
        auto NextMinimum{precedence + 1};
        auto RightResult{ParseExpression(NextMinimum)};
        if (!RightResult)
        {
            return RightResult;
        }
        left = std::make_unique<BinaryOpNode>(OperatorToken, std::move(left), std::move(*RightResult));
    }
    return left;
}
std::expected<std::unique_ptr<ASTNode>, std::string> parser::ParseUnary()
{
    if (depth >= MaxDepth)
    {
        return std::unexpected{"表达式嵌套过深"};
    }
    DepthGuard guard{depth};
    auto &Token{Current()};
    if (Token.IsMinus())
    {
        Advance();
        auto operand{ParseUnary()};
        if (!operand)
        {
            return operand;
        }
        return std::make_unique<UnaryMinusNode>(std::move(*operand));
    }
    if (Token.IsStar())
    {
        Advance();
        auto address{ParseUnary()};
        if (!address)
        {
            return address;
        }
        // 默认按 4 字节解引用。
        return std::make_unique<DereferenceNode>(std::move(*address), 4);
    }
    if (Token.IsReadMemory8() || Token.IsReadMemory16() ||
        Token.IsReadMemory32() || Token.IsReadMemory64())
    {
        auto size{Token.IsReadMemory8()
                      ? 1
                      : (Token.IsReadMemory16() ? 2
                                                : (Token.IsReadMemory32() ? 4 : 8))};
        Advance();
        if (!Current().IsLeftParen())
        {
            return std::unexpected(std::format("位置{0}处read{1}后缺少左括号", Current().GetPosition(), size * 8));
        }
        Advance();
        auto address{ParseExpression(0)};
        if (!address)
        {
            return address;
        }
        if (!Current().IsRightParen())
        {
            return std::unexpected(std::format("位置{0}处read{1}缺少右括号", Current().GetPosition(), size * 8));
        }
        Advance();
        return std::make_unique<DereferenceNode>(std::move(*address), size);
    }
    return ParsePrimary();
}
std::expected<std::unique_ptr<ASTNode>, std::string> parser::ParsePrimary()
{
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
        auto inner{ParseExpression(0)};
        if (!inner)
        {
            return inner;
        }
        if (!Current().IsRightParen())
        {
            return std::unexpected(std::format("位置{0}处缺少右括号", Current().GetPosition()));
        }
        Advance();
        return std::make_unique<ParenthesizedNode>(std::move(*inner));
    }
    return std::unexpected(std::format("位置{0}处意外的'{1}'", Token.GetPosition(), Token.GetText()));
}
