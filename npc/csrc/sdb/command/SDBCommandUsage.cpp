#include "SDBCommandUsage.hpp"
SDBCommandUsage::SDBCommandUsage(std::string_view arguments, std::string_view description) noexcept
    : Arguments(arguments), Description(description)
{
}
std::string_view SDBCommandUsage::GetArguments() const noexcept
{
    return Arguments;
}
std::string_view SDBCommandUsage::GetDescription() const noexcept
{
    return Description;
}
