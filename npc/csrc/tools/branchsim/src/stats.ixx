export module stats;
import std;
// 统计: 总计 + 按记录类型分开计(条件分支/jal/call/ret/jalr), 避免某类接近100%的命中率淹没其他类的差异
export struct stats
{
    std::size_t total{};
    std::size_t correct{};
    std::size_t branch_total{};
    std::size_t branch_correct{};
    std::size_t jal_total{};
    std::size_t jal_correct{};
    std::size_t call_total{};
    std::size_t call_correct{};
    std::size_t ret_total{};
    std::size_t ret_correct{};
    std::size_t jalr_total{};
    std::size_t jalr_correct{};

    [[nodiscard]] auto Mispred() const -> std::size_t;
    [[nodiscard]] auto Accuracy() const -> double;
    [[nodiscard]] auto BranchAccuracy() const -> double;
    [[nodiscard]] auto JalAccuracy() const -> double;
    [[nodiscard]] auto CallAccuracy() const -> double;
    [[nodiscard]] auto RetAccuracy() const -> double;
    [[nodiscard]] auto JalrAccuracy() const -> double;
};
export void PrintTable(std::span<const std::pair<std::string_view, stats>> results);
export void WriteCSV(std::span<const std::pair<std::string_view, stats>> results,
                     const std::filesystem::path &dir,
                     std::string_view tag = "");
