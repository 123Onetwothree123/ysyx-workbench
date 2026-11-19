module;
#include <cstdint>

module AlwaysJump;
Prediction AlwaysJump::predict(Addr pc) const
{
    // 方向型参考算法，不提供目标。
    return {true, false, 0};
}
void AlwaysJump::update(Addr pc, bool taken, Addr target, BranchKind kind,
                        std::uint32_t)
{
    // predict 不查 BTB，更新不影响预测。
    btb.update(pc, target, kind);
}
std::string_view AlwaysJump::GetName() const
{
    return name;
}
AlwaysJump::AlwaysJump(const BPConfig &config) : BPAlgorithmBase{config}
{
}
