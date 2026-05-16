#include "DifftestCPUState.hpp"
#include <VRV32E32Reg.h>
#include <print>
#include <stdexcept>
#include "sdb/SDBDPI.hpp"

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
//
DifftestCPUState DifftestCPUState::ReadDUTState(VRV32E32Reg &Top)
{
    auto State{DifftestCPUState{}};
    for (std::size_t Index{0}; Index < State.gpr.size(); ++Index)
    {
        const auto RegisterIndex{static_cast<std::int32_t>(Index)};
        static_cast<void>(CPP_NPCGetGPR(RegisterIndex)); // 调用DPI获取GPR值以触发Verilator更新
        Top.eval();                                      // 执行Verilator组合逻辑求值
        State.gpr[Index] = CPP_NPCGetGPR(RegisterIndex);
    }
    State.gpr[0] = 0; // x0寄存器输出恒为 0
    State.pc = CPP_NPCGetPC();
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
