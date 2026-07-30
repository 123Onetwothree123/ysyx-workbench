module;
#include <cstdint>
export module DCache;
import std;
import DCacheConfig;
import AccessRecord;

export enum struct ReplPolicy
{
    FIFO,
    LRU,
    Random,
};

export struct AccessOutcome
{
    bool hit{};      // 这次访问是否命中
    bool writeback{}; // 这次访问是否换出了脏块(需要写回内存)
};

export class DCache
{
private:
    struct entry
    {
        bool valid{};         // 1代表有数据
        std::uint32_t tag{};  // 数据块号 = 地址 >> offset_bits
        bool dirty{};         // 写回法: 1表示被写过, 换出时要写回
        std::uint64_t time{}; // FIFO记插入时间, LRU记最后访问时间
    };
    // 行=组数=2^index_bits，每行ways项，举个例子：num_blocks=16，ways=2就是8行×2项=16个entry
    std::vector<std::vector<entry>> sets;
    std::size_t index_bits{};  // 组索引位数，2底数，也就是log2(组数)
    std::size_t offset_bits{}; // 块内偏移位数，也就是log2(块大小)
    std::size_t ways{};
    ReplPolicy policy{};
    std::string name;
    std::uint64_t tick{}; // 逻辑时钟，给FIFO/LRU计时用
    void fill(entry& e, std::uint32_t tag, bool is_write);
public:
    DCache(const DCacheConfig& config, ReplPolicy policy, std::string_view name);
    ~DCache() = default;
    AccessOutcome access(const AccessRecord& rec); // 读命中/写命中都算hit
    [[nodiscard]] std::string_view GetName() const;
};
