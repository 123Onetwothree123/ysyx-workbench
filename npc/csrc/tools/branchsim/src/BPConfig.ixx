export module BPConfig;
import std;
export class BPConfig
{
private:
    std::size_t btb_bits{BTB_BITS};         // 表项数=2^N
    std::size_t btb_ways{BTB_WAYS};         // 相联度，1=直接映射，表项数=全相联
    std::size_t jal_btb_bits{JAL_BTB_BITS}; // 独立jal BTB的索引位数
    std::size_t jal_btb_ways{JAL_BTB_WAYS}; // 独立jal BTB的相联度
    std::size_t ras_bits{RAS_BITS};         // RAS深度=2^N
public:
    BPConfig() = default;
    // DSE用, 手动指定参数
    BPConfig(std::size_t bits, std::size_t ways, std::size_t jal_bits, std::size_t jal_ways,
             std::size_t ras_bits);
    ~BPConfig() = default;
    std::size_t get_btb_bits() const;
    std::size_t get_btb_ways() const;
    std::size_t get_jal_btb_bits() const;
    std::size_t get_jal_btb_ways() const;
    std::size_t get_ras_bits() const;
};
