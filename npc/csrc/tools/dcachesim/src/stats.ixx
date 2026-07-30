export module stats;
import std;
export struct stats
{
    std::size_t total{};
    std::size_t hits{};
    std::size_t reads{};
    std::size_t read_hits{};
    std::size_t writes{};
    std::size_t write_hits{};
    std::size_t writebacks{}; // 脏块被换出的次数

    [[nodiscard]] auto Misses() const -> std::size_t;
    [[nodiscard]] auto HitRate() const -> double;
};
export void PrintTable(std::span<const std::pair<std::string_view, stats>> results);
export void WriteCSV(std::span<const std::pair<std::string_view, stats>> results, const std::filesystem::path& dir);
