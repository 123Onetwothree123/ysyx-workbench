module;
#include "common/DseRunner.hpp"
module dse;
import std;
import types;
import trace;
import cache;

auto run_dse(const std::string& trace_path,
             std::span<const unsigned> block_sizes,
             std::span<const unsigned> num_blocks_list,
             std::span<const unsigned> ways_list,
             std::span<const unsigned> repl_list) -> std::vector<DseEntry>
{
    std::vector<Addr> trace_data;
    {
        TraceReader reader(trace_path);
        while (auto addr = reader.next())
            trace_data.push_back(*addr);
    }

    auto configs{common::EnumerateCombos<CacheConfig>(
        {block_sizes, num_blocks_list, ways_list, repl_list},
        [](std::span<const unsigned> value)
        {
            return CacheConfig{value[0], value[1], value[2], value[3]};
        },
        [](const CacheConfig& config)
        {
            return common::ValidWays(config.ways, config.num_blocks);
        })};

    auto results{common::EvalCombos<CacheConfig, DseEntry>(configs, 1,
        [&](const CacheConfig& config, std::span<DseEntry> rows)
        {
            CacheSim sim(config);
            for (auto addr : trace_data)
                sim.access(addr);
            rows[0] = {config, sim.stats()};
        })};

    common::SortByDescending(results, [](const DseEntry& entry)
    {
        const auto& s{entry.stats};
        return s.total > 0 ? static_cast<double>(s.hits) / s.total : 0.0;
    });

    return results;
}
