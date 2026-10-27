module;
export module npc.difftest.DifftestCPUState;
import npc.config;
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
    /// @brief 逐寄存器比对 REF（this）与 DUT 状态；只判定，不打印
    /// @param DUT DUT 寄存器状态
    /// @return 全部一致返回 true，否则返回 false
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

/// @brief NEMU 可选浮点 DiffTest ABI 的固定布局：freg[32] + fcsr。
///
/// 槽位固定为 64 位，与 DUT 的 XLEN 无关。RV32F（FLEN=32）只有低 32 位
/// 有效，高 32 位在读取后统一清零；NEMU 内部对单精度使用 NaN boxing，
/// 因此 TO_REF 方向会写入 0xffffffff 高半区，不能把未装箱的 0 送进去。
export class DifftestFPState
{
public:
    static constexpr std::size_t RegCount{32};
    static constexpr std::uint64_t FRegMask{
        npc::XLen == 32 ? 0xffff'ffffULL : ~0ULL};

    [[nodiscard]] std::uint64_t GetFReg(std::size_t Index) const;
    void SetFReg(std::size_t Index, std::uint64_t Value);
    [[nodiscard]] std::uint64_t GetFCSR() const;
    void SetFCSR(std::uint64_t Value);
    /// @brief 从 DUT 的退休镜像读取 f0-f31/fcsr；FLEN=32 时清零高半区
    static DifftestFPState ReadDUTState(DUT &dut);
    /// @brief 逐 f 寄存器与 fcsr 比对；只判定，不打印
    [[nodiscard]] bool Check(const DifftestFPState &DUT) const;
    /// @brief 把 ABI 值归一化为本 DUT 可比较的宽度（RV32 只保留低 32 位）
    void NormalizeFromABI();
    /// @brief TO_REF 前按 NEMU RV32F 的 NaN boxing 约定补齐高 32 位
    void PrepareForREF();
private:
    std::array<std::uint64_t, RegCount> freg{};
    std::uint64_t fcsr{};
};
