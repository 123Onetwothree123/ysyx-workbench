export module stats;
import std;
// 统计: 总计 + 按记录类型(条件分支/jal)分开计, 避免jal接近100%的命中率淹没分支准确率差异
export struct stats
{
    std::size_t total{};
    std::size_t correct{};
    std::size_t branch_total{};
    std::size_t branch_correct{};
    std::size_t jal_total{};
    std::size_t jal_correct{};

    [[nodiscard]] auto Mispred() const -> std::size_t;
    [[nodiscard]] auto Accuracy() const -> double;
    [[nodiscard]] auto BranchAccuracy() const -> double;
    [[nodiscard]] auto JalAccuracy() const -> double;
};
export void PrintTable(std::span<const std::pair<std::string_view, stats>> results);
export void WriteCSV(std::span<const std::pair<std::string_view, stats>> results,
                     const std::filesystem::path &dir,
                     std::string_view tag = "");
