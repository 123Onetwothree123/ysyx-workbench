export module BpConfig;
import std;
export class BpConfig
{
private:
    std::size_t btb_bits{BTB_BITS}; // 表项数=2^N
    std::size_t btb_ways{BTB_WAYS}; // 相联度，1=直接映射，表项数=全相联
public:
    std::size_t get_btb_bits();
    std::size_t get_btb_ways();
};