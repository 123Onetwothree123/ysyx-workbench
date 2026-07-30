module;
#include <cstdint>

module BTFN;
bool BTFN::predict(uint32_t pc) const
{
    auto hit{btb.lookup(pc)};
    if (hit)
        return hit->target < pc;
    return false;
}
void BTFN::update(uint32_t pc, bool taken, uint32_t target, BranchKind kind)
{
    // 基线=当前硬件行为: jal不进BTB(必然误预测重定向), 只记录条件分支
    if (kind == BranchKind::Branch)
        btb.update(pc, target, false);
}
std::string_view BTFN::GetName() const
{
    return name;
}
BTFN::BTFN(const BPConfig &config) : BPAlgorithmBase{config}
{
}
