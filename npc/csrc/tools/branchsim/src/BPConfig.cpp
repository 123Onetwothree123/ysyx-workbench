module BPConfig;
import std;
std::size_t BPConfig::get_btb_bits() const
{
    return btb_bits;
}
std::size_t BPConfig::get_btb_ways() const
{
    return btb_ways;
}