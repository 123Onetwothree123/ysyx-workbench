module;
#include <cstdint>
module DCache;
import std;
import DCacheConfig;
import AccessRecord;

static std::size_t log2_ceil(std::size_t n)
{
    std::size_t r{0};
    while ((std::size_t{1} << r) < n)
    {
        ++r;
    }
    return r;
}

DCache::DCache(const DCacheConfig& config, ReplPolicy policy, std::string_view name)
    : index_bits{log2_ceil(config.get_num_blocks() / config.get_ways())}
    , offset_bits{log2_ceil(config.get_block_size())}
    , ways{config.get_ways()}
    , policy{policy}
    , name{name}
{
    auto SetsNumber{std::size_t{1} << index_bits};
    sets.resize(SetsNumber);
    for (auto& set : sets)
    {
        set.resize(ways); // entry全部默认初始化为0
    }
}

void DCache::fill(entry& e, std::uint32_t tag, bool is_write)
{
    e.valid = true;
    e.tag = tag;
    e.dirty = is_write; // 写分配: 写缺失也把块装进来并置脏
    e.time = tick;
}

AccessOutcome DCache::access(const AccessRecord& rec)
{
    ++tick;
    const auto tag{rec.GetAddr() >> offset_bits};
    const auto index{tag & ((std::size_t{1} << index_bits) - 1)}; // 取低index_bits位作为组号
    auto& set{sets[index]};

    // 第1轮：找是否有同 tag 的块，命中
    for (auto& e : set)
    {
        if (e.valid && e.tag == tag)
        {
            if (policy == ReplPolicy::LRU)
            {
                e.time = tick; // LRU命中要刷新访问时间
            }
            if (rec.GetIsWrite())
            {
                e.dirty = true; // 写命中置脏
            }
            return {true, false};
        }
    }
    // 第2轮：找空闲槽位
    for (auto& e : set)
    {
        if (!e.valid)
        {
            fill(e, tag, rec.GetIsWrite());
            return {false, false};
        }
    }
    // 第3轮：组满了，按替换策略选牺牲者
    std::size_t victim{0};
    if (policy == ReplPolicy::Random)
    {
        victim = static_cast<std::size_t>(std::rand()) % set.size();
    }
    else
    {
        // FIFO比插入时间，LRU比访问时间，都是取time最小者
        for (std::size_t i{1}; i < set.size(); ++i)
        {
            if (set[i].time < set[victim].time)
            {
                victim = i;
            }
        }
    }
    const auto writeback{set[victim].dirty}; // 脏块换出要写回内存
    fill(set[victim], tag, rec.GetIsWrite());
    return {false, writeback};
}

std::string_view DCache::GetName() const
{
    return name;
}
