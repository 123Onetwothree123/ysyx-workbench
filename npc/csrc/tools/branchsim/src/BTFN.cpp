module;
#include <cstdint>

module BTFN;
Prediction BTFN::predict(uint32_t pc) const
{
    auto hit{btb.lookup(pc)};
    if (hit)
        return {hit->target < pc, true, hit->target};
    return {false, false, 0};
}
void BTFN::update(uint32_t pc, bool taken, uint32_t target, BranchKind kind)
{
    // 基线=当前硬件行为: 只有条件分支进BTB, jal/jalr/ret不预测(必然误预测重定向)
    if (kind == BranchKind::Branch)
        btb.update(pc, target, kind);
}
std::string_view BTFN::GetName() const
{
    return name;
}
BTFN::BTFN(const BPConfig &config) : BPAlgorithmBase{config}
{
}
