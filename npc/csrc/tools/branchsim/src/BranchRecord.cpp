module BranchRecord;
import std;
BranchRecord::BranchRecord(std::uint32_t p, std::uint32_t t, bool tk, BranchKind k)
    : pc{p}, target{t}, taken{tk}, kind{k}
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
