module;
#include <cstdint>

module BTFNSplitJalRAS;
Prediction BTFNSplitJalRAS::predict(uint32_t pc) const
{
    if (auto h{jal_btb.lookup(pc)})
    {
        if (h->kind == BranchKind::Ret)
        {
            // Ret/Coroutine表项保存静态immediate，目标与RTL一样为
            // (RAS top + immediate) & ~1；栈空则无法预测。
            if (auto t{ras.top_addr()})
                return {true, true, (*t + h->target) & ~std::uint32_t{1}};
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
void BTFNSplitJalRAS::update(uint32_t pc, bool taken, uint32_t target,
                             BranchKind kind, std::uint32_t jalr_immediate)
{
    switch (kind)
    {
    case BranchKind::Branch:
        // RTL的BTFN表只训练后向条件分支。
        if (target < pc)
            btb.update(pc, target, kind);
        break;
    case BranchKind::Jal:
        jal_btb.update(pc, target, kind);
        break;
    case BranchKind::Call: // jal ra直接调用: 目标静态可BTB预测, 同时压RAS
        ras.push(pc + 4);
        jal_btb.update(pc, target, kind);
        break;
    case BranchKind::JalrCall: // 普通间接call: 压RAS，并学习本次实际目标
        ras.push(pc + 4);
        jal_btb.update(pc, target, BranchKind::Call);
        break;
    case BranchKind::Ret: // 只弹RAS，Ret表项保存静态immediate
        ras.pop();
        jal_btb.update(pc, jalr_immediate, BranchKind::Ret);
        break;
    case BranchKind::Coroutine: // x1/x5异名link hint：RTL为pop-then-push
        ras.pop();
        ras.push(pc + 4);
        jal_btb.update(pc, jalr_immediate, BranchKind::Ret);
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
