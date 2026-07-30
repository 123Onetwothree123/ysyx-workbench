module BPConfig;
import std;
BPConfig::BPConfig(std::size_t bits, std::size_t ways, std::size_t jal_bits, std::size_t jal_ways)
    : btb_bits{bits}, btb_ways{ways}, jal_btb_bits{jal_bits}, jal_btb_ways{jal_ways}
{
}
std::size_t BPConfig::get_btb_bits() const
{
    return btb_bits;
}
std::size_t BPConfig::get_btb_ways() const
{
    return btb_ways;
}
std::size_t BPConfig::get_jal_btb_bits() const
{
    return jal_btb_bits;
}
std::size_t BPConfig::get_jal_btb_ways() const
{
    return jal_btb_ways;
}
