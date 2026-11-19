module;
#include "common/CsvResult.hpp"
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
    constexpr std::string_view Header{
        "算法,总分支,正确,误预测,准确率(%),"
        "分支总数,分支正确,分支准确率(%),jal总数,jal正确,jal准确率(%),"
        "call总数,call正确,call准确率(%),ret总数,ret正确,ret准确率(%),"
        "jalr总数,jalr正确,jalr准确率(%)"};

    auto filename{tag.empty() ? std::string{"result.csv"}
                              : std::format("result-{}.csv", tag)};
    common::WriteCsvFile(dir / filename, Header, results, [](const auto& row)
    {
        const auto &[name, s]{row};
        return std::vector<std::string>{
            std::format("{}", name),
            std::format("{}", s.total),
            std::format("{}", s.correct),
            std::format("{}", s.Mispred()),
            std::format("{:.2f}", s.Accuracy()),
            std::format("{}", s.branch_total),
            std::format("{}", s.branch_correct),
            std::format("{:.2f}", s.BranchAccuracy()),
            std::format("{}", s.jal_total),
            std::format("{}", s.jal_correct),
            std::format("{:.2f}", s.JalAccuracy()),
            std::format("{}", s.call_total),
            std::format("{}", s.call_correct),
            std::format("{:.2f}", s.CallAccuracy()),
            std::format("{}", s.ret_total),
            std::format("{}", s.ret_correct),
            std::format("{:.2f}", s.RetAccuracy()),
            std::format("{}", s.jalr_total),
            std::format("{}", s.jalr_correct),
            std::format("{:.2f}", s.JalrAccuracy())};
    });
}
