module;
#include <cstdint>
#include <format>
#include <stdexcept>
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
    // 这里只做判定，不打印。失败时由 difftest.cpp 汇总完整报告，
    // 避免“只报第一个差异”与完整报告重复刷屏。
    for (std::size_t Index{0}; Index < gpr.size(); ++Index)
    {
        if (gpr[Index] != DUT.gpr[Index])
            return false;
    }
    return pc == DUT.pc;
}
int DifftestCPUState::GetDirectionToDUT()
{
    return Direction::DIFFTEST_TO_DUT;
}
int DifftestCPUState::GetDirectionToRef()
{
    return Direction::DIFFTEST_TO_REF;
}
static_assert(std::is_standard_layout_v<DifftestFPState>);
static_assert(sizeof(DifftestFPState) == 33 * sizeof(std::uint64_t));
std::uint64_t DifftestFPState::GetFReg(std::size_t Index) const
{
    if (Index >= freg.size())
    {
        throw std::out_of_range{"DifftestFPState::GetFReg: 索引超出范围"};
    }
    return freg[Index];
}
void DifftestFPState::SetFReg(std::size_t Index, std::uint64_t Value)
{
    if (Index >= freg.size())
    {
        throw std::out_of_range{"DifftestFPState::SetFReg: 索引超出范围"};
    }
    freg[Index] = Value & FRegMask;
}
std::uint64_t DifftestFPState::GetFCSR() const
{
    return fcsr;
}
void DifftestFPState::SetFCSR(std::uint64_t Value)
{
    // fflags[4:0] + frm[7:5]；其余位不属于架构状态。
    fcsr = Value & 0xffU;
}
DifftestFPState DifftestFPState::ReadDUTState(DUT &dut)
{
    DifftestFPState State{};
    for (std::size_t Index{0}; Index < State.freg.size(); ++Index)
    {
        const auto Value{dut.ReadCachedFPR(static_cast<std::uint32_t>(Index))};
        if (!Value)
        {
            throw std::runtime_error{std::format(
                "DifftestFPState: 读取 DUT f{} 失败：{}", Index, Value.error())};
        }
        State.freg[Index] = *Value & FRegMask;
    }
    State.fcsr = dut.ReadCachedFCSR() & 0xffU;
    return State;
}
bool DifftestFPState::Check(const DifftestFPState &DUT) const
{
    for (std::size_t Index{0}; Index < freg.size(); ++Index)
    {
        if ((freg[Index] & FRegMask) != (DUT.freg[Index] & FRegMask))
            return false;
    }
    return (fcsr & 0xffU) == (DUT.fcsr & 0xffU);
}
void DifftestFPState::NormalizeFromABI()
{
    for (auto &Value : freg)
        Value &= FRegMask;
    fcsr &= 0xffU;
}
void DifftestFPState::PrepareForREF()
{
    NormalizeFromABI();
#if CONFIG_XLEN == 32
    // RV32F 的 REF 用 64 位槽位做单精度 NaN boxing。ABI 文档建议高 32 位
    // 为 0，但 NEMU 的运行时表示要求高 32 位全 1；在 TO_REF 边界补齐，
    // 既保留低 32 位架构值，又不会让 REF 把 0 误判成 canonical NaN。
    for (auto &Value : freg)
        Value |= 0xffff'ffff'0000'0000ULL;
#endif
}
