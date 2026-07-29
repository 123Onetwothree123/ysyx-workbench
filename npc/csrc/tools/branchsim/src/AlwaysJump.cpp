module;
#include <cstdint>
module AlwaysJump;
bool AlwaysJump::predict(uint32_t pc) const
{
    return true;
}
void AlwaysJump::update(uint32_t pc, bool taken, uint32_t target)
{
    btb.update(pc, target);
}
std::string_view AlwaysJump::GetName() const
{
    return name;
}
AlwaysJump::AlwaysJump(const BPConfig &config) : BPAlgorithmBase(config)
{
}