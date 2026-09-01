export module cache;
import std;
import types;

export class CacheSim {
public:
    explicit CacheSim(const CacheConfig& cfg);
    auto access(std::uint32_t addr) -> AccessResult;
    [[nodiscard]] auto config() const -> const CacheConfig& { return config_; }
    [[nodiscard]] auto stats() const -> const SimStats& { return stats_; }

private:
    struct Block {
        bool          valid       = false;
        std::uint32_t tag         = 0;
        std::uint64_t access_time = 0;
        std::uint64_t insert_time = 0;
    };

    CacheConfig                     config_;
    unsigned                        offset_bits_ = 0;
    unsigned                        index_bits_  = 0;
    unsigned                        num_sets_    = 0;
    std::vector<std::vector<Block>> sets_;
    std::uint64_t                   tick_ = 0;
    std::mt19937                    rng_{std::random_device{}()};

    // 3C 模型辅助状态
    std::unordered_set<std::uint32_t> seen_tags_;
    std::vector<Block>                fa_blocks_;
    unsigned                          fa_ways_ = 0;

    SimStats stats_;

    auto find_victim(const std::vector<Block>& set) -> unsigned;
    auto fa_access(std::uint32_t tag) -> bool;
    auto log2_ceil(unsigned n) -> unsigned;
};
