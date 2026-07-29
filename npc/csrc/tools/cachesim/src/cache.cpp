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

auto CacheSim::find_in_set(const std::vector<Block>& set, std::uint32_t tag) -> int
{
    for (int i = 0; i < static_cast<int>(set.size()); ++i) {
        if (set[i].valid && set[i].tag == tag)
            return i;
    }
    return -1;
}

auto CacheSim::find_victim(const std::vector<Block>& set) -> unsigned
{
    unsigned victim = 0;

    // 优先选无效块
    for (unsigned i = 0; i < set.size(); ++i) {
        if (!set[i].valid)
            return i;
    }

    switch (config_.repl_policy) {
    case 0: { // FIFO
        std::uint64_t oldest = set[0].insert_time;
        for (unsigned i = 1; i < set.size(); ++i) {
            if (set[i].insert_time < oldest) {
                oldest = set[i].insert_time;
                victim = i;
            }
        }
        break;
    }
    case 1: { // LRU
        std::uint64_t oldest = set[0].access_time;
        for (unsigned i = 1; i < set.size(); ++i) {
            if (set[i].access_time < oldest) {
                oldest = set[i].access_time;
                victim = i;
            }
        }
        break;
    }
    case 2: // Random
    default:
        victim = static_cast<unsigned>(std::rand()) % set.size();
        break;
    }

    return victim;
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
    int   hit_way  = find_in_set(set, tag);

    if (hit_way >= 0) {
        // 命中
        set[hit_way].access_time = tick_;
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
