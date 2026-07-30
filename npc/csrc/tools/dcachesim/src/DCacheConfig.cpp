module DCacheConfig;
import std;
DCacheConfig::DCacheConfig(std::size_t bs, std::size_t nb, std::size_t w)
    : block_size{bs}, num_blocks{nb}, ways{w}
{
}
std::size_t DCacheConfig::get_block_size() const
{
    return block_size;
}
std::size_t DCacheConfig::get_num_blocks() const
{
    return num_blocks;
}
std::size_t DCacheConfig::get_ways() const
{
    return ways;
}
