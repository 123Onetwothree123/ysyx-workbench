module BPAlgorithmsType;
import std;
import BPAlgorithmBase;
import AlwaysJump;
import BTFN;
import BTFNSharedJal;
import BTFNSplitJal;
import BPConfig;

BPAlgorithmsType::BPAlgorithmsType(const BPConfig& config)
{
    algos.push_back(std::make_unique<AlwaysJump>(config));
    algos.push_back(std::make_unique<BTFN>(config));
    algos.push_back(std::make_unique<BTFNSharedJal>(config));
    algos.push_back(std::make_unique<BTFNSplitJal>(config));
}
auto BPAlgorithmsType::begin() const noexcept
{
    return algos.begin();
}
auto BPAlgorithmsType::end() const noexcept
{
    return algos.end();
}
std::size_t BPAlgorithmsType::size() const noexcept
{
    return algos.size();
}
BPAlgorithmBase& BPAlgorithmsType::operator[](std::size_t i) const
{
    return *algos[i];
}
