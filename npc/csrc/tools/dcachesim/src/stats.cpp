module;
#include "common/CsvResult.hpp"
module stats;
import std;
auto stats::Misses() const -> std::size_t
{
    return total - hits;
}
auto stats::HitRate() const -> double
{
    return total ? 100.0 * hits / total : 0.0;
}
void PrintTable(std::span<const std::pair<std::string_view, stats>> results)
{
    std::println("{:<10} {:>8} {:>8} {:>8} {:>8} {:>8} {:>8} {:>8} {:>8} {:>10}",
                 "策略", "总访问", "命中", "缺失", "读", "读命中", "写", "写命中", "写回", "命中率");
    std::println("{:-^94}", "");

    for (auto& [name, s] : results)
    {
        std::println("{:<10} {:>8} {:>8} {:>8} {:>8} {:>8} {:>8} {:>8} {:>8} {:>9.2f}%",
                     name, s.total, s.hits, s.Misses(), s.reads, s.read_hits,
                     s.writes, s.write_hits, s.writebacks, s.HitRate());
    }
}
void WriteCSV(std::span<const std::pair<std::string_view, stats>> results, const std::filesystem::path& dir)
{
    constexpr std::string_view Header{"策略,总访问,命中,缺失,读,读命中,写,写命中,写回,命中率(%)"};

    common::WriteCsvFile(dir / "result.csv", Header, results, [](const auto& row)
    {
        const auto& [name, s]{row};
        return std::vector<std::string>{
            std::format("{}", name),
            std::format("{}", s.total),
            std::format("{}", s.hits),
            std::format("{}", s.Misses()),
            std::format("{}", s.reads),
            std::format("{}", s.read_hits),
            std::format("{}", s.writes),
            std::format("{}", s.write_hits),
            std::format("{}", s.writebacks),
            std::format("{:.2f}", s.HitRate())};
    });
}
