export module DCacheConfig;
import std;
export class DCacheConfig
{
private:
    std::size_t block_size{DCACHE_BLOCK_SIZE}; // 块大小(字节, 2的幂)
    std::size_t num_blocks{DCACHE_NUM_BLOCKS}; // cache块总数
    std::size_t ways{DCACHE_WAYS};             // 相联度, 1=直接映射, 块总数=全相联
public:
    DCacheConfig() = default;
    DCacheConfig(std::size_t bs, std::size_t nb, std::size_t w); // DSE用, 手动指定参数
    ~DCacheConfig() = default;
    std::size_t get_block_size() const;
    std::size_t get_num_blocks() const;
    std::size_t get_ways() const;
};
