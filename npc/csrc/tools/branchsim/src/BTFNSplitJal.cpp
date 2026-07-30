module;
#include <cstdint>

module BTFNSplitJal;
Prediction BTFNSplitJal::predict(uint32_t pc) const
{
    // 与硬件一致: 取指时不知道指令类型, 两张表同时按PC查
    // jal表Jal/Call表项: 目标静态必正确; Ret表项: 目标是上次返回地址(接受校验, 通常不准)
    if (auto h{jal_btb.lookup(pc)})
        return {true, true, h->target};
    auto hit{btb.lookup(pc)};
    if (hit)
        return {hit->target < pc, true, hit->target};
    return {false, false, 0};
}
void BTFNSplitJal::update(uint32_t pc, bool taken, uint32_t target, BranchKind kind)
{
    // jal BTB只收静态目标可预测的Jal/Call和需要标记的Ret; 间接jalr目标多变, 不入表
    if (kind == BranchKind::Jal || kind == BranchKind::Call || kind == BranchKind::Ret)
        jal_btb.update(pc, target, kind);
    else if (kind == BranchKind::Branch)
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
