module BranchRecord;
import std;
BranchRecord::BranchRecord(Addr p, Addr t, bool tk,
                           BranchKind k, std::uint32_t immediate)
    : pc{p}, target{t}, taken{tk}, kind{k}, jalr_immediate{immediate}
{
}
Addr BranchRecord::GetPC() const{
    return pc;
}
Addr BranchRecord::GetTarget() const{
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
