export module types;
import std;

// 目标 XLEN 编译期由 CONFIG_XLEN 决定，机器字统一用 Addr。
export inline constexpr unsigned kXlen = CONFIG_XLEN;
static_assert(kXlen == 32 || kXlen == 64,
              "CONFIG_XLEN 必须是 32 或 64");

export using Addr = std::conditional_t<kXlen == 64, std::uint64_t, std::uint32_t>;

export struct CacheConfig {
    unsigned block_size  = CACHE_BLOCK_SIZE;
    unsigned num_blocks  = CACHE_NUM_BLOCKS;
    unsigned ways        = CACHE_WAYS;
    unsigned repl_policy = CACHE_REPL_POLICY;
};

export enum struct MissType {
    Hit        = 0,
    Compulsory = 1,
    Capacity   = 2,
    Conflict   = 3,
};

export struct AccessResult {
    MissType type = MissType::Hit;
};

export struct SimStats {
    unsigned total           = 0;
    unsigned hits            = 0;
    unsigned miss_compulsory = 0;
    unsigned miss_capacity   = 0;
    unsigned miss_conflict   = 0;

    [[nodiscard]] auto misses() const -> unsigned {
        return miss_compulsory + miss_capacity + miss_conflict;
    }
};
