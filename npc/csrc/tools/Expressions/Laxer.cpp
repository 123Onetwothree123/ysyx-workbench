#include "Laxer.hpp"

#include <cctype>

Laxer::Laxer(std::string_view input)
{
    this->input = input;
}
bool Laxer::HasError() const noexcept
{
    return !error.empty();
}
token Laxer::next()
{
    if (HasError())
    {
        return token::MakeEnd(position);
    }
    SkipWhitespace();
    if (IsAtEnd())
    {
        return token::MakeEnd(position);
    }
    char current{Peek()};
    if (current == '0' && position + 1 < input.size() && (input[position + 1] == 'x' || input[position + 1] == 'X'))
    {
        return ScanHexNumber();
    }
    if (std::isdigit(static_cast<unsigned char>(current)))
    {
        return ScanNumber();
    }
    if (std::isalpha(static_cast<unsigned char>(current)) || current == '_' || current == '$')
    {
        return ScanRegister();
    }
    return ScanOperator();
}
std::string Laxer::GetError() const noexcept
{
    return error;
}
std::expected<token, std::string> Laxer::scan()
{
    if (HasError())
    {
        return std::unexpected(error);
    }
    token Token{next()};
    if (HasError())
    {
        return std::unexpected(error);
    }
    return Token;
}
std::expected<std::vector<token>, std::string> Laxer::ScanAll()
{
    std::vector<token> tokens;
    while (true)
    {
        auto result{scan()};
        if (!result)
        {
            return std::unexpected(result.error());
        }
        tokens.push_back(*result);
        if (result->IsEndOfInput())
        {
            break;
        }
    }
    return tokens;
}
void Laxer::SkipWhitespace()
{
    while (!IsAtEnd() && std::isspace(static_cast<unsigned char>(Peek())))
    {
        Advance();
    }
}
bool Laxer::IsAtEnd() const noexcept
{
    return position >= input.size();
}
char Laxer::Peek() const noexcept
{
    if (IsAtEnd())
    {
        return '\0';
    }
    return input[position];
}
char Laxer::Advance() noexcept
{
    if (!IsAtEnd())
    {
        return input[position++];
    }
    return '\0';
}
bool Laxer::Match(char expected) noexcept
{
    if (Peek() == expected)
    {
        Advance();
        return true;
    }
    return false;
}
token Laxer::ScanNumber()
{
    auto start{position};
    while (!IsAtEnd() && std::isdigit(static_cast<unsigned char>(Peek())))
    {
        Advance();
    }
    auto text {input.substr(start, position - start)};
    try
    {
        auto value {std::stoul(std::string(text), nullptr, 10)};
        return token::MakeNumber(text, value, start);
    }
    catch (const std::exception &)
    {
        error = "无效的编号: " + std::string(text);
        return token::MakeEnd(position);
    }
}
token Laxer::ScanHexNumber()
{
    auto start{position};
    Advance(); // 跳过0
    Advance(); // 跳过x和X
    auto DigitStart{position};
    while (!IsAtEnd() && std::isxdigit(static_cast<unsigned char>(Peek())))
    {
        Advance();
    }
    auto text{input.substr(start, position - start)};
    if (position == DigitStart)
    {
        error = "无效的十六进制数字: " + std::string(text);
        return token::MakeEnd(position);
    }
    try
    {
        auto value{std::stoul(std::string(text), nullptr, 16)};
        return token::MakeNumber(text, static_cast<std::uint32_t>(value), start);
    }
    catch (const std::exception &)
    {
        error = "无效的十六进制数字: " + std::string(text);
        return token::MakeEnd(position);
    }
}
token Laxer::ScanRegister()
{
    auto start{position};
    const auto HasDollarPrefix{Peek() == '$'};
    if (HasDollarPrefix)
    {
        Advance();
        if (IsAtEnd() || !(std::isalnum(static_cast<unsigned char>(Peek())) || Peek() == '_'))
        {
            error = "无效的寄存器名: " + std::string(input.substr(start, position - start));
            return token::MakeEnd(position);
        }
    }
    while (!IsAtEnd() && (std::isalnum(static_cast<unsigned char>(Peek())) || Peek() == '_'))
    {
        Advance();
    }
    auto text{input.substr(start, position - start)};
    if (!HasDollarPrefix && text == "read8")
    {
        return token::MakeReadMemory8(start);
    }
    if (!HasDollarPrefix && text == "read16")
    {
        return token::MakeReadMemory16(start);
    }
    if (!HasDollarPrefix && text == "read32")
    {
        return token::MakeReadMemory32(start);
    }
    // 这里的reg_index暂时设为（问过AI，建议的），后面解析的时候会根据text来确定真正的reg_index
    return token::MakeRegister(text, 0, start);
}
token Laxer::ScanOperator(){
    auto start{position};
    char current{Peek()};
    Advance();
    if (current == '(')
    {
        return token::MakeLeftParen(start);
    }
    if (current == ')')
    {
        return token::MakeRightParen(start);
    }
    if (current == '+')
    {
        return token::MakePlus(input.substr(start, 1), start);
    }
    if (current == '-')
    {
        return token::MakeMinus(input.substr(start, 1), start);
    }
    if (current == '*')
    {
        return token::MakeStar(start);
    }
    if (current == '/')
    {
        return token::MakeSlash(start);
    }
    if (current == '=' && Match('='))
    {
        return token::MakeEqual(start);
    }
    if (current == '!' && Match('='))
    {
        return token::MakeNotEqual(start);
    }
    if (current == '<' && Match('='))
    {
        return token::MakeLessEqual(start);
    }
    if (current == '&' && Match('&'))
    {
        return token::MakeLogicalAnd(start);
    }
    error = "未知的字符: " + std::string(1, current);
    return token::MakeEnd(position);
}
