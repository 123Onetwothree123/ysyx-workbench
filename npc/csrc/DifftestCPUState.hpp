#ifndef DIFFTEST_CPU_STATE_HPP
#define DIFFTEST_CPU_STATE_HPP
#include <array>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
class VRV32E32Reg;
class DifftestCPUState
{
public:
    [[nodiscard]] std::uint32_t GetGPR(std::size_t Index) const;
    void SetGPR(std::size_t Index, std::uint32_t Value);
    [[nodiscard]] std::uint32_t GetPC() const;

    /// @brief 设置程序计数器 PC 的值
    /// @param Value 要设置的 PC 值
    void SetPC(std::uint32_t Value);

    /// @brief 从 DUT 顶层读出全部寄存器状态（32 个 GPR + PC）
    /// @param Top Verilator 生成的顶层模块引用
    /// @return 包含 GPR 和 PC 的 CPU 状态快照
    static DifftestCPUState ReadDUTState(VRV32E32Reg &Top);

    /// @brief 逐寄存器比对 REF（this）与 DUT 状态，不一致时打印差异并返回 false
    /// @param DUT DUT 寄存器状态
    /// @return 全部一致返回 true，否则返回 false
    [[nodiscard]] bool CheckRegs(const DifftestCPUState &DUT) const;

    /// @brief 获取 DIFFTEST_TO_DUT 方向值（用于 difftest_regcpy）
    /// @return 方向常量 0
    [[nodiscard]] static constexpr int GetDirectionToDUT() { return Direction::DIFFTEST_TO_DUT; }
    /// @brief 获取 DIFFTEST_TO_REF 方向值（用于 difftest_regcpy）
    /// @return 方向常量 1
    [[nodiscard]] static constexpr int GetDirectionToRef() { return Direction::DIFFTEST_TO_REF; }
private:
    std::array<std::uint32_t, 32> gpr{}; ///< 32 个通用寄存器（x0–x31），值初始化为 0
    std::uint32_t pc{};
    enum Direction
    {
        DIFFTEST_TO_DUT = 0,
        DIFFTEST_TO_REF = 1,
    };
};

#endif
