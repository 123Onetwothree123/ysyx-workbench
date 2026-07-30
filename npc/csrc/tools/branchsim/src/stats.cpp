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
auto stats::BranchAccuracy() const -> double
{
    return branch_total ? 100.0 * branch_correct / branch_total : 0.0;
}
auto stats::JalAccuracy() const -> double
{
    return jal_total ? 100.0 * jal_correct / jal_total : 0.0;
}
void PrintTable(std::span<const std::pair<std::string_view, stats>> results)
{
    std::println("{:<20} {:>10} {:>10} {:>10} {:>10} {:>12} {:>12}",
                 "算法", "总分支", "正确", "误预测", "准确率", "分支准确率", "jal准确率");
    std::println("{:-^84}", "");

    for (auto &[name, s] : results)
    {
        std::println("{:<20} {:>10} {:>10} {:>10} {:>9.2f}% {:>11.2f}% {:>11.2f}%",
                     name, s.total, s.correct, s.Mispred(), s.Accuracy(),
                     s.BranchAccuracy(), s.JalAccuracy());
    }
}
void WriteCSV(std::span<const std::pair<std::string_view, stats>> results,
              const std::filesystem::path &dir,
              std::string_view tag)
{
    std::filesystem::create_directories(dir);
    auto filename{tag.empty() ? std::string{"result.csv"}
                              : std::format("result-{}.csv", tag)};
    std::ofstream file{dir / filename};
    file << "算法,总分支,正确,误预测,准确率(%),分支总数,分支正确,分支准确率(%),jal总数,jal正确,jal准确率(%)\n";
    for (auto &[name, s] : results)
    {
        file << std::format("{},{},{},{},{:.2f},{},{},{:.2f},{},{},{:.2f}\n",
                            name, s.total, s.correct, s.Mispred(), s.Accuracy(),
                            s.branch_total, s.branch_correct, s.BranchAccuracy(),
                            s.jal_total, s.jal_correct, s.JalAccuracy());
    }
}
