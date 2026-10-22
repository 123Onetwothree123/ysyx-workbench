module;
#include <stdio.h>
#include "../Config.h"
module npc.difftest.DifftestCPUState;
static_assert(std::is_standard_layout_v<DifftestCPUState>);
static_assert(sizeof(DifftestCPUState) == 33 * sizeof(npc::XWord));
npc::XWord DifftestCPUState::GetGPR(std::size_t Index) const
{
    if (Index >= gpr.size())
    {
        throw std::out_of_range{"DifftestCPUState::GetGPR: 索引超出范围"};
    }
    return gpr[Index];
}
void DifftestCPUState::SetGPR(std::size_t Index, npc::XWord Value)
{
    if (Index >= gpr.size())
    {
        throw std::out_of_range{"DifftestCPUState::SetGPR: 索引超出范围"};
    }
    gpr[Index] = Value;
}
//一个月前本来打算直接列表的，但是发现跑起来的时候就出现了大量的测试点
npc::XWord DifftestCPUState::GetPC() const
{
    return pc;
}
//同上
void DifftestCPUState::SetPC(npc::XWord Value)
{
    pc = Value;
}
DifftestCPUState DifftestCPUState::ReadDUTState(DUT &dut)
{
    auto State{DifftestCPUState{}};
    for (std::size_t Index{0}; Index < State.gpr.size(); ++Index)
    {
        auto result{dut.ReadCachedGPR(static_cast<std::uint32_t>(Index))};
        // DUT 缓存已经是完整 XWord，这里禁止再向 32 位截断。
        State.gpr[Index] = result ? *result : npc::XWord{0};
    }
    State.pc = dut.ReadCachedPC();
    return State;
}
bool DifftestCPUState::CheckRegs(const DifftestCPUState &DUT) const
{
    for (std::size_t Index{0}; Index < gpr.size(); ++Index)
    {
        if (gpr[Index] != DUT.gpr[Index])
        {
            std::println(stderr,
                         "DiffTest 不匹配: x{} 参考实现 = 0x{:0{}x}, DUT = 0x{:0{}x}, DUT pc = 0x{:0{}x}",
                         Index, gpr[Index], npc::XHexDigits, DUT.gpr[Index],
                         npc::XHexDigits, DUT.pc, npc::XHexDigits);
            return false;
        }
    }
    if (pc != DUT.pc)
    {
        std::println(stderr,
                     "DiffTest 不匹配: pc 参考实现 = 0x{:0{}x}, DUT = 0x{:0{}x}",
                     pc, npc::XHexDigits, DUT.pc, npc::XHexDigits);
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
