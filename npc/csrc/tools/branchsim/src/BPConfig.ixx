export module BPConfig;
import std;
export class BPConfig
{
private:
    std::size_t btb_bits{BTB_BITS}; // 表项数=2^N
    std::size_t btb_ways{BTB_WAYS}; // 相联度，1=直接映射，表项数=全相联
public:
    BPConfig() = default;
    ~BPConfig() = default;
    std::size_t get_btb_bits() const;
    std::size_t get_btb_ways() const;
};