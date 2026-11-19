module;
#include <cstdint>

module BTFNSplitJal;
Prediction BTFNSplitJal::predict(Addr pc) const
{
    // 两张表同时查；jal 表命中即 taken。
    if (auto h{jal_btb.lookup(pc)})
        return {true, true, h->target};
    auto hit{btb.lookup(pc)};
    if (hit)
        return {hit->target < pc, true, hit->target};
    return {false, false, 0};
}
void BTFNSplitJal::update(Addr pc, bool taken, Addr target, BranchKind kind,
                          std::uint32_t)
{
    // 间接 call 用实际目标训练 jal BTB。
    if (kind == BranchKind::Jal || kind == BranchKind::Call || kind == BranchKind::Ret)
        jal_btb.update(pc, target, kind);
    else if (kind == BranchKind::JalrCall)
        jal_btb.update(pc, target, BranchKind::Call);
    else if (kind == BranchKind::Branch && target < pc)
        btb.update(pc, target, kind);
}
std::string_view BTFNSplitJal::GetName() const
{
    return name;
}
BTFNSplitJal::BTFNSplitJal(const BPConfig &config)
    : BPAlgorithmBase{config},
      jal_btb{config.get_jal_btb_bits(), config.get_jal_btb_ways()}
{
}
