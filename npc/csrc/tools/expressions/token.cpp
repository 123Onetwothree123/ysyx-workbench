module npc.expressions.token;
token::token(Kind InputKind, std::string_view InputText, std::uint32_t InputValue, std::size_t InputPosition)
{
    kind = InputKind;
    text = InputText;
    value = InputValue;
    position = InputPosition;
}
token token::MakeNumber(std::string_view text, std::uint32_t value, std::size_t position)
{
    return token(Kind::Number, text, value, position);
}
token token::MakeRegister(std::string_view text, std::uint32_t RegisterIndex, std::size_t position)
{
    return token(Kind::Register, text, RegisterIndex, position);
}
token token::MakePlus(std::string_view text, std::size_t position)
{
    return token(Kind::Plus, text, 0, position);
}
token token::MakeMinus(std::string_view text, std::size_t position)
{
    return token(Kind::Minus, text, 0, position);
}
token token::MakeStar(std::size_t position)
{
    return token(Kind::Star, "*", 0, position);
}
token token::MakeSlash(std::size_t position)
{
    return token(Kind::Slash, "/", 0, position);
}
token token::MakeEqual(std::size_t position)
{
    return token(Kind::Equal, "==", 0, position);
}
token token::MakeNotEqual(std::size_t position)
{
    return token(Kind::NotEqual, "!=", 0, position);
}
token token::MakeLessEqual(std::size_t position)
{
    return token(Kind::LessEqual, "<=", 0, position);
}
token token::MakeLogicalAnd(std::size_t position)
{
    return token(Kind::LogicalAnd, "&&", 0, position);
}
token token::MakeLeftParen(std::size_t position)
{
    return token(Kind::LeftParen, "(", 0, position);
}
token token::MakeRightParen(std::size_t position)
{
    return token(Kind::RightParen, ")", 0, position);
}
token token::MakeEnd(std::size_t position)
{
    return token(Kind::EndOfInput, "", 0, position);
}
token token::MakeReadMemory8(std::size_t position)
{
    return token(Kind::ReadMemory8, "read8", 0, position);
}
token token::MakeReadMemory16(std::size_t position)
{
    return token(Kind::ReadMemory16, "read16", 0, position);
}
token token::MakeReadMemory32(std::size_t position)
{
    return token(Kind::ReadMemory32, "read32", 0, position);
}
bool token::IsEndOfInput() const noexcept
{
    return kind == Kind::EndOfInput;
}
bool token::IsNumber() const noexcept
{
    return kind == Kind::Number;
}
bool token::IsRegister() const noexcept
{
    return kind == Kind::Register;
}
bool token::IsPlus() const noexcept
{
    return kind == Kind::Plus;
}
bool token::IsMinus() const noexcept
{
    return kind == Kind::Minus;
}
bool token::IsStar() const noexcept
{
    return kind == Kind::Star;
}
bool token::IsSlash() const noexcept
{
    return kind == Kind::Slash;
}
bool token::IsEqual() const noexcept
{
    return kind == Kind::Equal;
}
bool token::IsNotEqual() const noexcept
{
    return kind == Kind::NotEqual;
}
bool token::IsLessEqual() const noexcept
{
    return kind == Kind::LessEqual;
}
bool token::IsLogicalAnd() const noexcept
{
    return kind == Kind::LogicalAnd;
}
bool token::IsLeftParen() const noexcept
{
    return kind == Kind::LeftParen;
}
bool token::IsRightParen() const noexcept
{
    return kind == Kind::RightParen;
}
bool token::IsReadMemory8() const noexcept
{
    return kind == Kind::ReadMemory8;
}
bool token::IsReadMemory16() const noexcept
{
    return kind == Kind::ReadMemory16;
}
bool token::IsReadMemory32() const noexcept
{
    return kind == Kind::ReadMemory32;
}
bool token::IsReadMemory() const noexcept
{
    return IsReadMemory8() || IsReadMemory16() || IsReadMemory32();
}
bool token::IsBinaryOperator() const noexcept
{
    return IsPlus() || IsMinus() || IsStar() || IsSlash() || IsEqual() || IsNotEqual() || IsLessEqual() || IsLogicalAnd();
}
bool token::IsUnaryOperator() const noexcept
{
    return IsMinus() || IsStar();
}
bool token::IsOperator() const noexcept
{
    return IsBinaryOperator() || IsUnaryOperator();
}
bool token::IsParenthesis() const noexcept
{
    return IsLeftParen() || IsRightParen();
}
int token::GetPrecedence() const noexcept
{
    if (IsLogicalAnd())
    {
        return 1;
    }
    if (IsEqual() || IsNotEqual() || IsLessEqual())
    {
        return 2;
    }
    if (IsPlus() || IsMinus())
    {
        return 3;
    }
    if (IsStar() || IsSlash())
    {
        return 4;
    }
    return 0; // 非运算符
}
bool token::IsRightAssociative() const noexcept
{
    return IsUnaryOperator();
}
std::string_view token::GetText() const noexcept
{
    return text;
}
std::uint32_t token::GetValue() const noexcept
{
    return value;
}
std::size_t token::GetPosition() const noexcept
{
    return position;
}
