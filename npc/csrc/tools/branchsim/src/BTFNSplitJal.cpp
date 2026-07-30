module;
#include <cstdint>

module BTFNSplitJal;
bool BTFNSplitJal::predict(uint32_t pc) const
{
    // 与硬件一致: 取指时不知道指令类型, 两张表同时按PC查
    if (jal_btb.lookup(pc))
        return true;
    auto hit{btb.lookup(pc)};
    return hit && hit->target < pc;
}
void BTFNSplitJal::update(uint32_t pc, bool taken, uint32_t target, BranchKind kind)
{
    if (kind == BranchKind::Jal)
        jal_btb.update(pc, target, true);
    else
        btb.update(pc, target, false);
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
