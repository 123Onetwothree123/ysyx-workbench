module;
#include <cstdint>

module BTFNSplitJal;
Prediction BTFNSplitJal::predict(Addr pc) const
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
void BTFNSplitJal::update(Addr pc, bool taken, Addr target, BranchKind kind,
                          std::uint32_t)
{
    // 与当前RTL一致：普通间接call也用本次解析出的实际目标
    // 训练独立JAL BTB，使稳定函数指针的后续调用可命中。
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
