export module dse;
import std;
import types;
import cache;

export struct DseEntry {
    CacheConfig config;
    SimStats    stats;
};

export auto run_dse(const std::string& trace_path,
                    std::span<const unsigned> block_sizes,
                    std::span<const unsigned> num_blocks_list,
                    std::span<const unsigned> ways_list,
                    std::span<const unsigned> repl_list) -> std::vector<DseEntry>;
