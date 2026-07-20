export module types;
import std;

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
