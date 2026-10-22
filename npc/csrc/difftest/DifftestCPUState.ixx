module;
#include "../Config.h"
export module npc.difftest.DifftestCPUState;
import std;
import npc.DUT;

export class DifftestCPUState
{
public:
    // REF ABI 的寄存器槽位宽度跟随构建 XLEN：RV32 为 32 位，
    // RV64 为 64 位，不能在 C++ 侧提前截断。
    [[nodiscard]] npc::XWord GetGPR(std::size_t Index) const;
    void SetGPR(std::size_t Index, npc::XWord Value);
    [[nodiscard]] npc::XWord GetPC() const;
    /// @brief 设置程序计数器PC的值
    /// @param Value 要设置的PC值
    void SetPC(npc::XWord Value);
    /// @brief 从DUT顶层读出全部寄存器状态（32 个GPR + PC）
    /// @param Top Verilator生成的顶层模块引用
    /// @return 包含GPR和PC的CPU状态快照
    static DifftestCPUState ReadDUTState(DUT &dut);
    /// @brief 逐寄存器比对REF（this）与DUT状态，不一致时打印差异并返回false
    /// @param DUT DUT寄存器状态
    /// @return 全部一致返回true，否则返回false
    [[nodiscard]] bool CheckRegs(const DifftestCPUState &DUT) const;
    /// @brief 获取DIFFTEST_TO_DUT方向值（用于difftest_regcpy）
    /// @return 方向常量 0
    [[nodiscard]] static int GetDirectionToDUT();
    /// @brief 获取DIFFTEST_TO_REF方向值（用于difftest_regcpy）
    /// @return 方向常量 1
    [[nodiscard]] static int GetDirectionToRef();
private:
    std::array<npc::XWord, 32> gpr{}; ///< 32 个通用寄存器（x0–x31），值初始化为 0
    npc::XWord pc{};
    enum Direction
    {
        DIFFTEST_TO_DUT = 0,
        DIFFTEST_TO_REF = 1,
    };
};
