#include "DUTControl.hpp"
#include <VRV32I.h>
DUTControl::DUTControl() : Top{std::make_unique<VRV32I>()}
{
}
DUTControl::~DUTControl()
{
    Final();
}
VRV32I &DUTControl::GetTop() noexcept
{
    return *Top;
}
const VRV32I &DUTControl::GetTop() const noexcept
{
    return *Top;
}
void DUTControl::Reset()
{
    Top->clock = 0;
    Top->reset = 1;
    Top->io_InstructionReadDATA = 0;
    Top->io_MemoryReadDATA = 0;
    Top->eval();
    Top->clock = 1;
    Top->eval();
    Top->clock = 0;
    Top->reset = 0;
    Top->eval();
}

void DUTControl::Step()
{
    Top->clock = 1;
    Top->eval();
    Top->clock = 0;
    Top->eval();
}
void DUTControl::Final()
{
    if (!Finalized)
    {
        Top->final();
        Finalized = true;
    }
}
