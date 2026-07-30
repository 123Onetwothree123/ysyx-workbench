module;
#include <cstdint>

module BTFNSharedJal;
bool BTFNSharedJal::predict(uint32_t pc) const
{
    auto hit{btb.lookup(pc)};
    return hit && (hit->is_jal || hit->target < pc);
}
void BTFNSharedJal::update(uint32_t pc, bool taken, uint32_t target, BranchKind kind)
{
    btb.update(pc, target, kind == BranchKind::Jal);
}
std::string_view BTFNSharedJal::GetName() const
{
    return name;
}
BTFNSharedJal::BTFNSharedJal(const BPConfig &config) : BPAlgorithmBase{config}
{
}
