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
    // 为保持旧CSV schema保留；write-through RTL不产生脏块，因此始终为0。
    std::size_t writebacks{};

    [[nodiscard]] auto Misses() const -> std::size_t;
    [[nodiscard]] auto HitRate() const -> double;
};
export void PrintTable(std::span<const std::pair<std::string_view, stats>> results);
export void WriteCSV(std::span<const std::pair<std::string_view, stats>> results, const std::filesystem::path& dir);
