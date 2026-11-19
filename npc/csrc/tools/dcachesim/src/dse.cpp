module;
#include "common/DseRunner.hpp"
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
    auto configs{common::EnumerateCombos<DCacheConfig>(
        {block_sizes, num_blocks_list, ways_list},
        [](std::span<const std::size_t> value)
        {
            return DCacheConfig{value[0], value[1], value[2]};
        },
        [](const DCacheConfig& config)
        {
            return common::ValidWays(config.get_ways(), config.get_num_blocks());
        })};

    constexpr std::size_t Policies{3};
    auto table{common::EvalCombos<DCacheConfig, DseEntry>(configs, Policies,
        [&](const DCacheConfig& config, std::span<DseEntry> rows)
        {
            DCachesType caches{config};
            for (std::size_t k{0}; k < caches.size(); ++k)
            {
                auto& e{rows[k]};
                e.block_size = config.get_block_size();
                e.num_blocks = config.get_num_blocks();
                e.ways = config.get_ways();
                e.policy = caches[k].GetName();
                e.st = RunOne(caches[k], trace);
            }
        })};

    common::SortByDescending(table, [](const DseEntry& e) { return e.st.HitRate(); });
    return table;
}

void WriteDseCSV(std::span<const DseEntry> results, const std::filesystem::path& base)
{
    constexpr std::string_view Header{
        "块大小,块总数,容量(B),相联度,策略,总访问,命中,缺失,读,读命中,写,写命中,写回,命中率(%)"};

    const auto path{common::WriteDseCsv(results, base, "", Header,
        [](const DseEntry& e)
        {
            return std::vector<std::string>{
                std::format("{}", e.block_size),
                std::format("{}", e.num_blocks),
                std::format("{}", e.block_size * e.num_blocks),
                std::format("{}", e.ways),
                std::format("{}", e.policy),
                std::format("{}", e.st.total),
                std::format("{}", e.st.hits),
                std::format("{}", e.st.Misses()),
                std::format("{}", e.st.reads),
                std::format("{}", e.st.read_hits),
                std::format("{}", e.st.writes),
                std::format("{}", e.st.write_hits),
                std::format("{}", e.st.writebacks),
                std::format("{:.2f}", e.st.HitRate())};
        })};
    std::println("DSE 完成，{} 组结果，结果保存在 {}", results.size(), path.string());
}
