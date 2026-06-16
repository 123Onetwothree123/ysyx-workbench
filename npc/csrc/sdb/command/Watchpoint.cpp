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
void Watchpoint::SetNO(std::size_t InputNO) noexcept
{
    this->NO = InputNO;
}
void Watchpoint::SetEnabled(bool InputEnabled) noexcept
{
    this->enabled = InputEnabled;
}
void Watchpoint::SetPC(std::size_t InputPC) noexcept
{
    this->PC = InputPC;
}
void Watchpoint::SetHasPC(bool InputHasPC) noexcept
{
    this->HasPC = InputHasPC;
}
const std::string &Watchpoint::GetExpression() const noexcept
{
    return expression;
}
void Watchpoint::SetExpression(const std::string &InputExpression) noexcept
{
    this->expression = InputExpression;
}
std::size_t Watchpoint::GetOldValue() const noexcept
{
    return OldValue;
}
void Watchpoint::SetOldValue(std::size_t InputOldValue) noexcept
{
    this->OldValue = InputOldValue;
}
