module;
#include <cstdint>

module BTFN;
bool BTFN::predict(uint32_t pc) const
{
    auto target{btb.lookup(pc)};
    if (target)
        return *target < pc;
    return false;
}
void BTFN::update(uint32_t pc, bool taken, uint32_t target)
{
    btb.update(pc, target);
}
std::string_view BTFN::GetName() const
{
    return name;
}
BTFN::BTFN(const BPConfig &config) : BPAlgorithmBase(config)
{
}