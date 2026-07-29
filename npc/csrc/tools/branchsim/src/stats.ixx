export module stats;
import std;
export struct stats
{
    std::size_t total{};
    std::size_t correct{};

    [[nodiscard]] auto Mispred() const -> std::size_t;
    [[nodiscard]] auto Accuracy() const -> double;
};
export void PrintTable(std::span<const std::pair<std::string_view, stats>> results);
export void WriteCSV(std::span<const std::pair<std::string_view, stats>> results, const std::filesystem::path &dir);