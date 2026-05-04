#include "DifftestCPUState.hpp"
#include <VRV32E32Reg.h>
#include <print>
#include <stdexcept>
#include "sdb/SDBDPI.hpp"

std::uint32_t DifftestCPUState::GetGPR(std::size_t Index) const
{
    if (Index >= gpr.size())
    {
        throw std::out_of_range{"DifftestCPUState::GetGPR: Index out of range"};
    }
    return gpr[Index];
}

void DifftestCPUState::SetGPR(std::size_t Index, std::uint32_t Value)
{
    if (Index >= gpr.size())
    {
        throw std::out_of_range{"DifftestCPUState::SetGPR: Index out of range"};
    }
    gpr[Index] = Value;
}

std::uint32_t DifftestCPUState::GetPC() const
{
    return pc;
}

void DifftestCPUState::SetPC(std::uint32_t Value)
{
    pc = Value;
}

DifftestCPUState DifftestCPUState::ReadDUTState(VRV32E32Reg &Top)
{
    DifftestCPUState State{};
    for (int Index = 0; Index < 32; ++Index)
    {
        static_cast<void>(CPP_NPCGetGPR(Index)); // 调用 DPI 获取 GPR 值以触发 Verilator 更新
        Top.eval();                               // 执行 Verilator 组合逻辑求值
        State.gpr[static_cast<std::size_t>(Index)] = CPP_NPCGetGPR(Index);
    }
    State.gpr[0] = 0; // x0 寄存器输出恒为 0
    State.pc = CPP_NpcGetPC();
    return State;
}

bool DifftestCPUState::CheckRegs(const DifftestCPUState &DUT) const
{
    for (std::size_t Index = 0; Index < gpr.size(); ++Index)
    {
        if (gpr[Index] != DUT.gpr[Index])
        {
            std::println(stderr,
                         "DiffTest mismatch: x{} ref = 0x{:08x}, dut = 0x{:08x}, dut pc = 0x{:08x}",
                         Index, gpr[Index], DUT.gpr[Index], DUT.pc);
            return false;
        }
    }
    if (pc != DUT.pc)
    {
        std::println(stderr,
                     "DiffTest mismatch: pc ref = 0x{:08x}, dut = 0x{:08x}",
                     pc, DUT.pc);
        return false;
    }
    return true;
}
