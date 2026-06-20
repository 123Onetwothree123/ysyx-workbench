module;
#include <cstdio>
module npc.difftest.DifftestCPUState;
std::uint32_t DifftestCPUState::GetGPR(std::size_t Index) const
{
    if (Index >= gpr.size())
    {
        throw std::out_of_range{"DifftestCPUState::GetGPR: 索引超出范围"};
    }
    return gpr[Index];
}
void DifftestCPUState::SetGPR(std::size_t Index, std::uint32_t Value)
{
    if (Index >= gpr.size())
    {
        throw std::out_of_range{"DifftestCPUState::SetGPR: 索引超出范围"};
    }
    gpr[Index] = Value;
}
//一个月前本来打算直接列表的，但是发现跑起来的时候就出现了大量的测试点
std::uint32_t DifftestCPUState::GetPC() const
{
    return pc;
}
//同上
void DifftestCPUState::SetPC(std::uint32_t Value)
{
    pc = Value;
}
DifftestCPUState DifftestCPUState::ReadDUTState(DUT &dut)
{
    auto State{DifftestCPUState{}};
    for (std::size_t Index{0}; Index < State.gpr.size(); ++Index)
    {
        auto result{dut.ReadGPR(static_cast<std::uint32_t>(Index))};
        State.gpr[Index] = result ? *result : 0;
    }
    auto pc{dut.ReadPC()};
    State.pc = pc ? *pc : 0;
    return State;
}
bool DifftestCPUState::CheckRegs(const DifftestCPUState &DUT) const
{
    for (std::size_t Index{0}; Index < gpr.size(); ++Index)
    {
        if (gpr[Index] != DUT.gpr[Index])
        {
            std::println(stderr,
                         "DiffTest 不匹配: x{} 参考实现 = 0x{:08x}, DUT = 0x{:08x}, DUT pc = 0x{:08x}",
                         Index, gpr[Index], DUT.gpr[Index], DUT.pc);
            return false;
        }
    }
    if (pc != DUT.pc)
    {
        std::println(stderr,
                     "DiffTest 不匹配: pc 参考实现 = 0x{:08x}, DUT = 0x{:08x}",
                     pc, DUT.pc);
        return false;
    }
    return true;
}
int DifftestCPUState::GetDirectionToDUT()
{
    return Direction::DIFFTEST_TO_DUT;
}
int DifftestCPUState::GetDirectionToRef()
{
    return Direction::DIFFTEST_TO_REF;
}
