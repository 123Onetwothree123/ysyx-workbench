module dse;
import std;
import trace;
import cache;

auto run_dse(const std::string& trace_path,
             std::span<const unsigned> block_sizes,
             std::span<const unsigned> num_blocks_list,
             std::span<const unsigned> ways_list,
             std::span<const unsigned> repl_list) -> std::vector<DseEntry>
{
    // 收集 trace 数据（只读一次）
    std::vector<std::uint32_t> trace_data;
    {
        TraceReader reader(trace_path);
        while (auto addr = reader.next())
            trace_data.push_back(*addr);
    }

    // 生成所有参数组合
    std::vector<CacheConfig> configs;
    for (auto bs : block_sizes) {
        for (auto nb : num_blocks_list) {
            for (auto w : ways_list) {
                if (w > nb || nb % w != 0) continue;
                for (auto r : repl_list) {
                    configs.push_back({bs, nb, w, r});
                }
            }
        }
    }

    // 并行评估
    std::vector<DseEntry> results(configs.size());
    std::vector<std::future<void>> futures;
    futures.reserve(configs.size());

    for (std::size_t i = 0; i < configs.size(); ++i) {
        futures.push_back(std::async(std::launch::async, [&, i] {
            CacheSim sim(configs[i]);
            for (auto addr : trace_data)
                sim.access(addr);
            results[i] = {configs[i], sim.stats()};
        }));
    }

    for (auto& f : futures)
        f.get();

    // 按命中率降序排列
    std::ranges::sort(results, [](const DseEntry& a, const DseEntry& b) {
        auto ra = a.stats.total > 0 ? static_cast<double>(a.stats.hits) / a.stats.total : 0.0;
        auto rb = b.stats.total > 0 ? static_cast<double>(b.stats.hits) / b.stats.total : 0.0;
        return ra > rb;
    });

    return results;
}
