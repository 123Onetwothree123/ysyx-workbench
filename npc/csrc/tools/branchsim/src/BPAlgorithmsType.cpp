module BPAlgorithmsType;

import std;
import BPAlgorithmBase;
import AlwaysJump;
import BTFN;

BPAlgorithmsType::BPAlgorithmsType()
{
    algos.push_back(std::make_unique<AlwaysJump>());
    algos.push_back(std::make_unique<BTFN>());
}

auto BPAlgorithmsType::begin() const noexcept { return algos.begin(); }
auto BPAlgorithmsType::end()   const noexcept { return algos.end(); }