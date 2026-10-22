module;
#include <cstdint>

module AlwaysJump;
Prediction AlwaysJump::predict(Addr pc) const
{
    // 方向型参考算法, 不提供目标(只做方向评判)
    return {true, false, 0};
}
void AlwaysJump::update(Addr pc, bool taken, Addr target, BranchKind kind,
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
