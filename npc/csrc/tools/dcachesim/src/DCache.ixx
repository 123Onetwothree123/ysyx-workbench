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
    bool hit{};
    // write-through/no-write-allocate，writeback 恒为 false。
    bool writeback{};
};

export class DCache
{
private:
    struct entry
    {
        bool valid{};
        Addr tag{};           // 块号 = 地址 >> offset_bits，宽度随 XLEN
        std::uint64_t time{}; // FIFO 插入时间 / LRU 最后访问时间
    };
    // 行=组数=2^index_bits，每行 ways 项。
    std::vector<entry> sets_flat;
    std::mdspan<entry, std::dextents<std::size_t, 2>> sets;
    std::size_t index_bits{};
    std::size_t offset_bits{};
    std::size_t ways{};
    ReplPolicy policy{};
    std::string name;
    std::uint64_t tick{}; // FIFO/LRU 逻辑时钟
    std::mt19937 rng_{std::random_device{}()};
    void fill(entry& e, Addr tag);
public:
    DCache(const DCacheConfig& config, ReplPolicy policy, std::string_view name);
    ~DCache() = default;
    // 读 miss 分配；写 miss 落内存不分配，写 hit 计为命中。
    AccessOutcome access(const AccessRecord& rec);
    [[nodiscard]] std::string_view GetName() const;
};
