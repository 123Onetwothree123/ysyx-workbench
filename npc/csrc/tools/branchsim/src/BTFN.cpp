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
void BTFN::update(uint32_t pc, bool taken, uint32_t target, BranchKind kind,
                  std::uint32_t)
{
    // 当前RTL只为BTFN可能预测taken的后向条件分支分配BTB；
    // 前向分支始终预测不跳，不应占容量或驱逐有用的后向表项。
    if (kind == BranchKind::Branch && target < pc)
        btb.update(pc, target, kind);
}
std::string_view BTFN::GetName() const
{
    return name;
}
BTFN::BTFN(const BPConfig &config) : BPAlgorithmBase{config}
{
}
