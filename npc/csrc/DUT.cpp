#include "DUT.hpp"
DUT::DUT() : dut{std::make_unique<VysyxSoCFull>()} {}
VysyxSoCFull &DUT::operator*()
{
    return *dut;
}
VysyxSoCFull *DUT::operator->()
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
    // 同步复位必须有时钟边沿才能生效，先拉高 reset 跑几个周期
    for (int i = 0; i < 5; ++i)
    {
        dut->clock = 0;
        dut->eval();
        dut->clock = 1;
        dut->eval();
    }
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