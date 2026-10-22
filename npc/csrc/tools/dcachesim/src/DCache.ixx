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
    bool hit{}; // 这次访问是否命中
    // RTL DCache 是 write-through/no-write-allocate，不存在脏块写回。
    // 保留字段以维持现有统计/CSV schema，值始终为 false。
    bool writeback{};
};

export class DCache
{
private:
    struct entry
    {
        bool valid{};         // 1代表有数据
        Addr tag{};           // 数据块号 = 地址 >> offset_bits，宽度随 XLEN
        std::uint64_t time{}; // FIFO记插入时间, LRU记最后访问时间
    };
    // 行=组数=2^index_bits，每行ways项，举个例子：num_blocks=16，ways=2就是8行×2项=16个entry
    std::vector<entry> sets_flat;
    std::mdspan<entry, std::dextents<std::size_t, 2>> sets; // (组数 × 相联度) 二维视图
    std::size_t index_bits{};  // 组索引位数，2底数，也就是log2(组数)
    std::size_t offset_bits{}; // 块内偏移位数，也就是log2(块大小)
    std::size_t ways{};
    ReplPolicy policy{};
    std::string name;
    std::uint64_t tick{}; // 逻辑时钟，给FIFO/LRU计时用
    std::mt19937 rng_{std::random_device{}()};
    void fill(entry& e, Addr tag);
public:
    DCache(const DCacheConfig& config, ReplPolicy policy, std::string_view name);
    ~DCache() = default;
    // 读 miss 会分配；写 miss 只经外部写通道落内存，不改变 cache tag。
    // 写 hit 会更新 RTL 中的镜像数据，在 tag-only 模型中表现为一次命中。
    AccessOutcome access(const AccessRecord& rec);
    [[nodiscard]] std::string_view GetName() const;
};
