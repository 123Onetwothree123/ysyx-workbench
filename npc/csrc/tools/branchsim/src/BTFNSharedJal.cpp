module;
#include <cstdint>

module BTFNSharedJal;
Prediction BTFNSharedJal::predict(Addr pc) const
{
    auto hit{btb.lookup(pc)};
    if (!hit)
        return {false, false, 0};
    // 非条件表项命中即 taken；Ret 目标接受校验。
    return {hit->kind != BranchKind::Branch || hit->target < pc, true, hit->target};
}
void BTFNSharedJal::update(Addr pc, bool taken, Addr target, BranchKind kind,
                           std::uint32_t)
{
    // 条件分支仅保留后向表项。
    if (kind != BranchKind::Branch || target < pc)
        btb.update(pc, target, kind);
}
std::string_view BTFNSharedJal::GetName() const
{
    return name;
}
BTFNSharedJal::BTFNSharedJal(const BPConfig &config) : BPAlgorithmBase{config}
{
}
