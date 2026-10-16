module BranchRecord;
import std;
BranchRecord::BranchRecord(std::uint32_t p, std::uint32_t t, bool tk,
                           BranchKind k, std::uint32_t immediate)
    : pc{p}, target{t}, taken{tk}, kind{k}, jalr_immediate{immediate}
{
}
std::uint32_t BranchRecord::GetPC() const{
    return pc;
}
std::uint32_t BranchRecord::GetTarget() const{
    return target;
}
bool BranchRecord::GetTaken() const{
    return taken;
}
BranchKind BranchRecord::GetKind() const{
    return kind;
}
std::uint32_t BranchRecord::GetJalrImmediate() const{
    return jalr_immediate;
}
