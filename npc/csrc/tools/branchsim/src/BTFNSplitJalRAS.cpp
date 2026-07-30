module;
#include <cstdint>

module BTFNSplitJalRAS;
Prediction BTFNSplitJalRAS::predict(uint32_t pc) const
{
    if (auto h{jal_btb.lookup(pc)})
    {
        if (h->kind == BranchKind::Ret)
        {
            // ret: 目标取RAS栈顶(可能错, 必须接受目标校验); 栈空则无法预测
            if (auto t{ras.top_addr()})
                return {true, true, *t};
            return {false, false, 0};
        }
        // Jal/Call表项: 命中即taken, 目标静态必正确
        return {true, true, h->target};
    }
    auto hit{btb.lookup(pc)};
    if (hit)
        return {hit->target < pc, true, hit->target};
    return {false, false, 0};
}
void BTFNSplitJalRAS::update(uint32_t pc, bool taken, uint32_t target, BranchKind kind)
{
    switch (kind)
    {
    case BranchKind::Branch:
        btb.update(pc, target, kind);
        break;
    case BranchKind::Jal:
        jal_btb.update(pc, target, kind);
        break;
    case BranchKind::Call: // jal ra直接调用: 目标静态可BTB预测, 同时压RAS
        ras.push(pc + 4);
        jal_btb.update(pc, target, kind);
        break;
    case BranchKind::JalrCall: // jalr ra间接调用: 目标不可预测, 但要压RAS
        ras.push(pc + 4);
        break;
    case BranchKind::Ret: // ret: 弹RAS, 表项只作ret标记
        ras.pop();
        jal_btb.update(pc, target, kind);
        break;
    case BranchKind::JalrOther: // 其他间接跳转: 不预测
        break;
    }
}
std::string_view BTFNSplitJalRAS::GetName() const
{
    return name;
}
BTFNSplitJalRAS::BTFNSplitJalRAS(const BPConfig &config)
    : BPAlgorithmBase{config},
      jal_btb{config.get_jal_btb_bits(), config.get_jal_btb_ways()},
      ras{config.get_ras_bits()}
{
}
