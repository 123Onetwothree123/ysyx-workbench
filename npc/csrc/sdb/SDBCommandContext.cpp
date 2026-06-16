#include "SDBCommandContext.hpp"
DUT &SDBCommandContext::GetDUT() const noexcept
{
    return dut;
}
SDBCommandContext::SDBCommandContext(DUT &InputDUT)
    : dut{InputDUT}
{
}