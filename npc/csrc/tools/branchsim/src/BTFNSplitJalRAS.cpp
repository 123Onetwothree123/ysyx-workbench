module;
#include <cstdint>

module BTFNSplitJalRAS;
Prediction BTFNSplitJalRAS::predict(Addr pc) const
{
    if (auto h{jal_btb.lookup(pc)})
    {
        if (h->kind == BranchKind::Ret)
        {
            // Ret 目标 = (RAS top + sign_extend(imm)) & ~1；栈空不预测。
            if (auto t{ras.top_addr()})
            {
                const auto offset{static_cast<Addr>(static_cast<std::int32_t>(static_cast<std::uint32_t>(h->target)))};
                return {true, true, (*t + offset) & ~Addr{1}};
            }
            return {false, false, 0};
        }
        // Jal/Call 表项命中即 taken，目标静态正确。
        return {true, true, h->target};
    }
    auto hit{btb.lookup(pc)};
    if (hit)
        return {hit->target < pc, true, hit->target};
    return {false, false, 0};
}
void BTFNSplitJalRAS::update(Addr pc, bool taken, Addr target,
                             BranchKind kind, std::uint32_t jalr_immediate)
{
    switch (kind)
    {
    case BranchKind::Branch:
        if (target < pc)
            btb.update(pc, target, kind);
        break;
    case BranchKind::Jal:
        jal_btb.update(pc, target, kind);
        break;
    case BranchKind::Call: // jal ra：目标可预测，同时压栈
        ras.push(pc + 4);
        jal_btb.update(pc, target, kind);
        break;
    case BranchKind::JalrCall: // 间接 call：压栈并学习实际目标
        ras.push(pc + 4);
        jal_btb.update(pc, target, BranchKind::Call);
        break;
    case BranchKind::Ret: // 只弹栈，表项存立即数
        ras.pop();
        jal_btb.update(pc, jalr_immediate, BranchKind::Ret);
        break;
    case BranchKind::Coroutine: // x1/x5 异名 link：pop-then-push
        ras.pop();
        ras.push(pc + 4);
        jal_btb.update(pc, jalr_immediate, BranchKind::Ret);
        break;
    case BranchKind::JalrOther:
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
