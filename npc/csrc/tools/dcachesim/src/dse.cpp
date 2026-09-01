module dse;
import std;
import DCacheConfig;
import DCachesType;
import DCache;
import AccessRecord;
import Run;
import stats;

auto RunDse(std::span<const AccessRecord> trace,
            std::span<const std::size_t> block_sizes,
            std::span<const std::size_t> num_blocks_list,
            std::span<const std::size_t> ways_list) -> std::vector<DseEntry>
{
    // 生成所有合法参数组合
    std::vector<DCacheConfig> configs{};
    for (auto bs : block_sizes)
    {
        for (auto nb : num_blocks_list)
        {
            for (auto w : ways_list | std::views::filter([nb](auto ways) { return ways <= nb && nb % ways == 0; }))
            {
                configs.emplace_back(bs, nb, w);
            }
        }
    }

    // 每组配置一个线程, 线程内顺序跑三种替换策略
    constexpr std::size_t Policies{3};
    std::vector<std::array<DseEntry, Policies>> results{configs.size()};
    {
        std::vector<std::jthread> threads{};
        for (std::size_t i{0}; i < configs.size(); ++i)
        {
            threads.emplace_back([&, i]
            {
                DCachesType caches{configs[i]};
                for (std::size_t k{0}; k < caches.size(); ++k)
                {
                    auto& e{results[i][k]};
                    e.block_size = configs[i].get_block_size();
                    e.num_blocks = configs[i].get_num_blocks();
                    e.ways = configs[i].get_ways();
                    e.policy = caches[k].GetName();
                    e.st = RunOne(caches[k], trace);
                }
            });
        }
    }   // jthread 析构自动 join, 确保所有线程执行完毕

    // 展平并按命中率降序排列
    std::vector<DseEntry> table{};
    table.reserve(configs.size() * Policies);
    for (auto& row : results)
    {
        for (auto& e : row)
        {
            table.push_back(std::move(e));
        }
    }
    std::ranges::sort(table, [](const DseEntry& a, const DseEntry& b)
    {
        return a.st.HitRate() > b.st.HitRate();
    });
    return table;
}

void WriteDseCSV(std::span<const DseEntry> results, const std::filesystem::path& base)
{
    // 生成结果目录 dcachesim/TestResult/时间戳/
    const auto now{std::chrono::system_clock::now()};
    const auto time_t{std::chrono::system_clock::to_time_t(now)};
    const auto tm{*std::localtime(&time_t)};
    const auto dir{base / std::format("{:04}{:02}{:02}-{:02}{:02}{:02}",
        tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday, tm.tm_hour, tm.tm_min, tm.tm_sec)};
    std::filesystem::create_directories(dir);

    const auto result_path{dir / "result.csv"};
    std::ofstream file{result_path, std::ios::binary};
    file << "\xEF\xBB\xBF"; // UTF-8 BOM, Excel打开不乱码
    file << "块大小,块总数,容量(B),相联度,策略,总访问,命中,缺失,读,读命中,写,写命中,写回,命中率(%)\n";
    for (const auto& e : results)
    {
        file << std::format("{},{},{},{},{},{},{},{},{},{},{},{},{},{:.2f}\n",
                            e.block_size, e.num_blocks, e.block_size * e.num_blocks, e.ways,
                            e.policy, e.st.total, e.st.hits, e.st.Misses(), e.st.reads,
                            e.st.read_hits, e.st.writes, e.st.write_hits, e.st.writebacks,
                            e.st.HitRate());
    }
    std::println("DSE 完成，{} 组结果，结果保存在 {}", results.size(), result_path.string());
}
