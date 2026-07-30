module;
#include <cstdint>

module BTFNSharedJal;
Prediction BTFNSharedJal::predict(uint32_t pc) const
{
    auto hit{btb.lookup(pc)};
    if (!hit)
        return {false, false, 0};
    // 非条件分支表项(jal/call/ret/jalr)命中即taken; 条件分支按BTFN判方向
    // ret表项的目标是"上次返回地址", 接受目标校验(通常不准, 这正是需要RAS的原因)
    return {hit->kind != BranchKind::Branch || hit->target < pc, true, hit->target};
}
void BTFNSharedJal::update(uint32_t pc, bool taken, uint32_t target, BranchKind kind)
{
    // 共享表: 所有控制流指令共用一张BTB, 互相挤占
    btb.update(pc, target, kind);
}
std::string_view BTFNSharedJal::GetName() const
{
    return name;
}
BTFNSharedJal::BTFNSharedJal(const BPConfig &config) : BPAlgorithmBase{config}
{
}
