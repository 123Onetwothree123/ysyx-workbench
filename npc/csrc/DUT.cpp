#include "DUT.hpp"
DUT::DUT() : dut{std::make_unique<VRV32I>()} {}
VRV32I &DUT::operator*()
{
    return *dut;
}
VRV32I *DUT::operator->()
{
    return dut.get();
}
void DUT::eval()
{
    dut->eval();
}
void DUT::final()
{
    dut->final();
}
void DUT::reset()
{
    dut->clock = 0;
    dut->reset = 1;
    dut->io_Interrupt = 0;
    dut->reset = 0;
    cycle = 0;
}
void DUT::step()
{
    dut->clock = 0;
    dut->eval();
    dut->clock = 1;
    dut->eval();
    ++cycle;
}
void DUT::step(AXI &axi)
{
    dut->clock = 0;
    dut->eval();
    axi.eval(*dut); // 下降沿跑AXI的握手
    dut->clock = 1;
    dut->eval(); // 上升沿CPU跑
    ++cycle;
}
std::size_t DUT::GetCycle() const
{
    return cycle;
}