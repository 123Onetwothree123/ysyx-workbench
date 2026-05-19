#include "DUTControl.hpp"
#include <VRV32E32Reg.h>
DUTControl::DUTControl() : Top{std::make_unique<VRV32E32Reg>()}
{
}
DUTControl::~DUTControl()
{
    Final();
}
VRV32E32Reg &DUTControl::GetTop() noexcept
{
    return *Top;
}
const VRV32E32Reg &DUTControl::GetTop() const noexcept
{
    return *Top;
}
void DUTControl::Reset()
{
    Top->sdb_debug_clk = 0;
    Top->sdb_pc_write_en = 0;
    Top->sdb_pc_write_data = 0;
    Top->sdb_gpr_write_en = 0;
    Top->sdb_gpr_write_addr = 0;
    Top->sdb_gpr_write_data = 0;
    Top->clk = 0;
    Top->rst = 1;
    Top->eval();
    Top->clk = 1;
    Top->eval();
    Top->clk = 0;
    Top->rst = 0;
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
