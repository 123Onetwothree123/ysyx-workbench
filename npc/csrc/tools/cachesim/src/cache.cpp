module cache;
import std;

CacheSim::CacheSim(const CacheConfig& cfg)
    : config_(cfg)
{
    offset_bits_ = log2_ceil(cfg.block_size);
    num_sets_    = cfg.num_blocks / cfg.ways;
    index_bits_  = log2_ceil(num_sets_);

    // 实际 cache
    sets_.resize(num_sets_);
    for (auto& set : sets_)
        set.resize(cfg.ways);

    // 全相联对照 cache (1 set, num_blocks ways, 用于检测 capacity miss)
    fa_ways_ = cfg.num_blocks;
    fa_blocks_.resize(fa_ways_);
}

auto CacheSim::log2_ceil(unsigned n) -> unsigned
{
    unsigned r = 0;
    while ((1u << r) < n)
        ++r;
    return r;
}

auto CacheSim::find_victim(const std::vector<Block>& set) -> unsigned
{
    // 优先选无效块
    if (const auto it{std::ranges::find_if(set, [](const Block& b) { return !b.valid; })}; it != set.end())
        return static_cast<unsigned>(it - set.begin());

    switch (config_.repl_policy) {
    case 0: // FIFO
        return static_cast<unsigned>(
            std::ranges::min_element(set, std::ranges::less{}, &Block::insert_time) - set.begin());
    case 1: // LRU
        return static_cast<unsigned>(
            std::ranges::min_element(set, std::ranges::less{}, &Block::access_time) - set.begin());
    case 2: // Random
    default:
        return std::uniform_int_distribution<unsigned>{0, static_cast<unsigned>(set.size()) - 1}(rng_);
    }
}

auto CacheSim::fa_access(std::uint32_t tag) -> bool
{
    // 在全相联 cache 中查找
    for (auto& blk : fa_blocks_) {
        if (blk.valid && blk.tag == tag) {
            blk.access_time = tick_;
            return true;
        }
    }

    // 缺失: 找替换块并写入
    unsigned victim = 0;
    std::uint64_t oldest = fa_blocks_[0].access_time;
    for (unsigned i = 0; i < fa_ways_; ++i) {
        if (!fa_blocks_[i].valid) {
            victim = i;
            break;
        }
        if (fa_blocks_[i].access_time < oldest) {
            oldest = fa_blocks_[i].access_time;
            victim = i;
        }
    }

    fa_blocks_[victim].valid       = true;
    fa_blocks_[victim].tag         = tag;
    fa_blocks_[victim].access_time = tick_;
    fa_blocks_[victim].insert_time = tick_;
    return false;
}

auto CacheSim::access(std::uint32_t addr) -> AccessResult
{
    ++tick_;
    ++stats_.total;

    std::uint32_t tag = addr >> offset_bits_;
    unsigned index = (num_sets_ > 1) ? (tag & (num_sets_ - 1)) : 0;

    AccessResult result;

    // 判断 compulsory miss
    bool first_seen = !seen_tags_.contains(tag);
    if (first_seen)
        seen_tags_.insert(tag);

    // 判断 capacity miss (全相联对照 cache)
    bool fa_hit = fa_access(tag);

    // 查实际 cache
    auto& set      = sets_[index];
    const auto hit_way{std::ranges::find_if(set, [tag](const Block& b) { return b.valid && b.tag == tag; })};

    if (hit_way != set.end()) {
        // 命中
        hit_way->access_time = tick_;
        result.type = MissType::Hit;
        ++stats_.hits;
    } else {
        // 缺失: 按 3C 模型分类
        if (first_seen) {
            result.type = MissType::Compulsory;
            ++stats_.miss_compulsory;
        } else if (!fa_hit) {
            result.type = MissType::Capacity;
            ++stats_.miss_capacity;
        } else {
            result.type = MissType::Conflict;
            ++stats_.miss_conflict;
        }

        // 替换
        unsigned victim = find_victim(set);
        set[victim].valid       = true;
        set[victim].tag         = tag;
        set[victim].access_time = tick_;
        set[victim].insert_time = tick_;
    }

    return result;
}
