#include "command/SDBCommandContext.hpp"
SDBCommandContext::SDBCommandContext(VRV32E32Reg &Top, std::size_t &Cycles)
    : TopRef(Top), CyclesRef(Cycles)
{
}
VRV32E32Reg &SDBCommandContext::GetTop() const noexcept
{
    return TopRef;
}
std::size_t &SDBCommandContext::GetCycles() const noexcept
{
    return CyclesRef;
}
