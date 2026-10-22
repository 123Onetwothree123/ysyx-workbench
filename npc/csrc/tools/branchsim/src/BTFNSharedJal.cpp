module;
#include <cstdint>

module BTFNSharedJal;
Prediction BTFNSharedJal::predict(Addr pc) const
{
    auto hit{btb.lookup(pc)};
    if (!hit)
        return {false, false, 0};
    // 非条件分支表项(jal/call/ret/jalr)命中即taken; 条件分支按BTFN判方向
    // ret表项的目标是"上次返回地址", 接受目标校验(通常不准, 这正是需要RAS的原因)
    return {hit->kind != BranchKind::Branch || hit->target < pc, true, hit->target};
}
void BTFNSharedJal::update(Addr pc, bool taken, Addr target, BranchKind kind,
                           std::uint32_t)
{
    // 共享表中非条件跳转仍正常训练；条件分支与RTL一样
    // 只保留BTFN可能预测taken的后向表项。
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
