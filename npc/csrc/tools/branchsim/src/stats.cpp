module stats;
import std;
auto stats::Mispred() const -> std::size_t
{
    return total - correct;
}
auto stats::Accuracy() const -> double
{
    return total ? 100.0 * correct / total : 0.0;
}
void PrintTable(std::span<const std::pair<std::string_view, stats>> results)
{
    std::println("{:<20} {:>10} {:>10} {:>10} {:>10}",
                 "算法", "总分支", "正确", "误预测", "准确率");
    std::println("{:-^60}", "");

    for (auto &[name, s] : results)
    {
        std::println("{:<20} {:>10} {:>10} {:>10} {:>9.2f}%", name, s.total, s.correct, s.Mispred(), s.Accuracy());
    }
}
void WriteCSV(std::span<const std::pair<std::string_view, stats>> results, const std::filesystem::path &dir)
{
    std::filesystem::create_directories(dir);
    auto file = std::ofstream(dir / "result.csv");
    file << "算法,总分支,正确,误预测,准确率(%)\n";
    for (auto &[name, s] : results)
    {
        file << std::format("{},{},{},{:.2f}\n", name, s.total, s.correct, s.Accuracy());
    }
}