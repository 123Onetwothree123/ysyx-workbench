module BPAlgorithmsType;
import std;
import BPAlgorithmBase;
import AlwaysJump;
import BTFN;
import BPConfig;

BPAlgorithmsType::BPAlgorithmsType(const BPConfig& config)
{
    algos.push_back(std::make_unique<AlwaysJump>(config));
    algos.push_back(std::make_unique<BTFN>(config));
}
auto BPAlgorithmsType::begin() const noexcept
{
    return algos.begin();
}
auto BPAlgorithmsType::end() const noexcept
{
    return algos.end();
}