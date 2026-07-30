module BranchRecord;
import std;
BranchRecord::BranchRecord(std::uint32_t p, std::uint32_t t, bool tk)
    : pc{p}, target{t}, taken{tk}
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