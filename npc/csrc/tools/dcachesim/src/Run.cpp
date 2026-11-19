module;
#include "common/ThreadPool.hpp"
module Run;
import std;
import DCachesType;
import DCache;
import AccessRecord;
import stats;

auto RunOne(DCache& cache,
            std::span<const AccessRecord> trace) -> stats
{
    stats st{};
    for (const auto& rec : trace)
    {
        auto outcome{cache.access(rec)};
        ++st.total;
        if (outcome.hit)
        {
            ++st.hits;
        }
        if (rec.GetIsWrite())
        {
            ++st.writes;
            if (outcome.hit)
            {
                ++st.write_hits;
            }
        }
        else
        {
            ++st.reads;
            if (outcome.hit)
            {
                ++st.read_hits;
            }
        }
        if (outcome.writeback)
        {
            ++st.writebacks;
        }
    }
    return st;
}

auto Run(const DCachesType& caches,
         std::span<const AccessRecord> trace)
    -> std::vector<std::pair<std::string_view, stats>>
{
    const auto N{caches.size()};
    std::vector<stats> results{N};

    common::ParallelFor(N, [&](std::size_t i)
    {
        results[i] = RunOne(caches[i], trace);
    });

    std::vector<std::pair<std::string_view, stats>> table{};
    for (std::size_t i{0}; i < N; ++i)
    {
        table.emplace_back(caches[i].GetName(), std::move(results[i]));
    }
    return table;
}
