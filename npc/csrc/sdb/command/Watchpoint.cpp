#include "Watchpoint.hpp"
Watchpoint::Watchpoint(std::size_t NO, bool enabled, std::size_t PC, bool HasPC)
{
    this->NO = NO;
    this->enabled = enabled;
    this->PC = PC;
    this->HasPC = HasPC;
}
std::size_t Watchpoint::GetNO() const noexcept
{
    return NO;
}
bool Watchpoint::IsEnabled() const noexcept
{
    return enabled;
}
std::size_t Watchpoint::GetPC() const noexcept
{
    return PC;
}
bool Watchpoint::HasValidPC() const noexcept
{
    return HasPC;
}
void Watchpoint::SetNO(std::size_t NO) noexcept
{
    this->NO = NO;
}
void Watchpoint::SetEnabled(bool enabled) noexcept
{
    this->enabled = enabled;
}
void Watchpoint::SetPC(std::size_t PC) noexcept
{
    this->PC = PC;
}
void Watchpoint::SetHasPC(bool HasPC) noexcept
{
    this->HasPC = HasPC;
}
const std::string &Watchpoint::GetExpression() const noexcept
{
    return expression;
}
void Watchpoint::SetExpression(const std::string &expression) noexcept
{
    this->expression = expression;
}
std::size_t Watchpoint::GetOldValue() const noexcept
{
    return OldValue;
}
void Watchpoint::SetOldValue(std::size_t OldValue) noexcept
{
    this->OldValue = OldValue;
}
