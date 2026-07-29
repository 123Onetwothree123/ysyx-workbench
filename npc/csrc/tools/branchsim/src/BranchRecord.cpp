module BranchRecord;
import std;
std::uint32_t BranchRecord::GetPC() const{
    return pc;
}
std::uint32_t BranchRecord::GetTarget() const{
    return target;
}
bool BranchRecord::GetTaken() const{
    return taken;
}