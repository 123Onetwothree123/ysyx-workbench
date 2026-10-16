module;
#include <cstdint>

module AlwaysJump;
Prediction AlwaysJump::predict(uint32_t pc) const
{
    // 方向型参考算法, 不提供目标(只做方向评判)
    return {true, false, 0};
}
void AlwaysJump::update(uint32_t pc, bool taken, uint32_t target, BranchKind kind,
                        std::uint32_t)
{
    // 该方向型基线predict()不查BTB，这里的更新不影响预测结果。
    btb.update(pc, target, kind);
}
std::string_view AlwaysJump::GetName() const
{
    return name;
}
AlwaysJump::AlwaysJump(const BPConfig &config) : BPAlgorithmBase{config}
{
}
