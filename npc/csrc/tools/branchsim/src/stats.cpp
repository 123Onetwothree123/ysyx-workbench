module stats;
import std;
static auto pct(std::size_t correct, std::size_t total) -> double
{
    return total ? 100.0 * correct / total : 0.0;
}
auto stats::Mispred() const -> std::size_t
{
    return total - correct;
}
auto stats::Accuracy() const -> double
{
    return pct(correct, total);
}
auto stats::BranchAccuracy() const -> double
{
    return pct(branch_correct, branch_total);
}
auto stats::JalAccuracy() const -> double
{
    return pct(jal_correct, jal_total);
}
auto stats::CallAccuracy() const -> double
{
    return pct(call_correct, call_total);
}
auto stats::RetAccuracy() const -> double
{
    return pct(ret_correct, ret_total);
}
auto stats::JalrAccuracy() const -> double
{
    return pct(jalr_correct, jalr_total);
}
void PrintTable(std::span<const std::pair<std::string_view, stats>> results)
{
    std::println("{:<20} {:>10} {:>10} {:>10} {:>10} {:>10} {:>10} {:>10} {:>10} {:>10}",
                 "算法", "总分支", "正确", "误预测", "准确率", "分支", "jal", "call", "ret", "jalr");
    std::println("{:-^110}", "");

    for (auto &[name, s] : results)
    {
        std::println("{:<20} {:>10} {:>10} {:>10} {:>9.2f}% {:>9.2f}% {:>9.2f}% {:>9.2f}% {:>9.2f}% {:>9.2f}%",
                     name, s.total, s.correct, s.Mispred(), s.Accuracy(),
                     s.BranchAccuracy(), s.JalAccuracy(), s.CallAccuracy(),
                     s.RetAccuracy(), s.JalrAccuracy());
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
    file << "算法,总分支,正确,误预测,准确率(%),"
            "分支总数,分支正确,分支准确率(%),jal总数,jal正确,jal准确率(%),"
            "call总数,call正确,call准确率(%),ret总数,ret正确,ret准确率(%),"
            "jalr总数,jalr正确,jalr准确率(%)\n";
    for (auto &[name, s] : results)
    {
        file << std::format("{},{},{},{},{:.2f},{},{},{:.2f},{},{},{:.2f},{},{},{:.2f},{},{},{:.2f},{},{},{:.2f}\n",
                            name, s.total, s.correct, s.Mispred(), s.Accuracy(),
                            s.branch_total, s.branch_correct, s.BranchAccuracy(),
                            s.jal_total, s.jal_correct, s.JalAccuracy(),
                            s.call_total, s.call_correct, s.CallAccuracy(),
                            s.ret_total, s.ret_correct, s.RetAccuracy(),
                            s.jalr_total, s.jalr_correct, s.JalrAccuracy());
    }
}
