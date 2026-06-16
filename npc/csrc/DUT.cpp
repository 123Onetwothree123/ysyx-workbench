#include "DUT.hpp"
#include <format>
DUT::DUT() : dut{std::make_unique<VysyxSoCFull>()}
{
    dut->debug_gpr_raddr = 0;
}
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
    dut->debug_gpr_raddr = 0;
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
std::size_t DUT::GetCycle() const
{
    return cycle;
}
std::expected<std::uint32_t, std::string> DUT::ReadGPR(std::uint32_t index)
{
    if (index >= 32)
    {
        return std::unexpected{std::format("GPR编号都超31号了，跑个毛线啊", index)};
    }
    dut->debug_gpr_raddr = static_cast<CData>(index);
    dut->eval();
    return static_cast<std::uint32_t>(dut->debug_gpr_rdata);
}
std::expected<std::uint32_t, std::string> DUT::ReadPC()
{
    dut->eval();
    return static_cast<std::uint32_t>(dut->debug_pc);
}
