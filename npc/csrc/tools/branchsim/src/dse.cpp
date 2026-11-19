module;
#include "common/DseRunner.hpp"
module dse;
import std;
import BPConfig;
import BPAlgorithmsType;
import BPAlgorithmBase;
import BranchRecord;
import Run;
import stats;

auto RunDse(std::span<const BranchRecord> trace,
            std::span<const std::size_t> btb_bits_list,
            std::span<const std::size_t> btb_ways_list,
            std::span<const std::size_t> jal_bits_list,
            std::span<const std::size_t> jal_ways_list,
            std::span<const std::size_t> ras_bits_list) -> std::vector<DseEntry>
{
    // ways 超过组数无意义，跳过。
    auto configs{common::EnumerateCombos<BPConfig>(
        {btb_bits_list, btb_ways_list, jal_bits_list, jal_ways_list, ras_bits_list},
        [](std::span<const std::size_t> value)
        {
            return BPConfig{value[0], value[1], value[2], value[3], value[4]};
        },
        [](const BPConfig& config)
        {
            return config.get_btb_ways() <= (std::size_t{1} << config.get_btb_bits())
                && config.get_jal_btb_ways() <= (std::size_t{1} << config.get_jal_btb_bits());
        })};

    // BPAlgorithmsType 固定 5 个算法。
    constexpr std::size_t AlgosPerCombo{5};
    auto table{common::EvalCombos<BPConfig, DseEntry>(configs, AlgosPerCombo,
        [&](const BPConfig& config, std::span<DseEntry> rows)
        {
            BPAlgorithmsType algos{config};
            for (std::size_t k{0}; k < algos.size(); ++k)
            {
                auto& e{rows[k]};
                e.btb_bits = config.get_btb_bits();
                e.btb_ways = config.get_btb_ways();
                e.jal_btb_bits = config.get_jal_btb_bits();
                e.jal_btb_ways = config.get_jal_btb_ways();
                e.ras_bits = config.get_ras_bits();
                e.algo = algos[k].GetName();
                e.st = RunOne(algos[k], trace);
            }
        })};

    common::SortByDescending(table, [](const DseEntry& e) { return e.st.Accuracy(); });
    return table;
}

void WriteDseCSV(std::span<const DseEntry> results,
                 const std::filesystem::path& base,
                 std::string_view tag)
{
    constexpr std::string_view Header{
        "分支BTB组数,分支BTB相联度,jalBTB组数,jalBTB相联度,RAS深度,算法,"
        "总分支,正确,误预测,准确率(%),"
        "分支总数,分支正确,分支准确率(%),jal总数,jal正确,jal准确率(%),"
        "call总数,call正确,call准确率(%),ret总数,ret正确,ret准确率(%),"
        "jalr总数,jalr正确,jalr准确率(%)"};

    const auto path{common::WriteDseCsv(results, base, tag, Header,
        [](const DseEntry& e)
        {
            return std::vector<std::string>{
                std::format("{}", std::size_t{1} << e.btb_bits),
                std::format("{}", e.btb_ways),
                std::format("{}", std::size_t{1} << e.jal_btb_bits),
                std::format("{}", e.jal_btb_ways),
                std::format("{}", std::size_t{1} << e.ras_bits),
                std::format("{}", e.algo),
                std::format("{}", e.st.total),
                std::format("{}", e.st.correct),
                std::format("{}", e.st.Mispred()),
                std::format("{:.2f}", e.st.Accuracy()),
                std::format("{}", e.st.branch_total),
                std::format("{}", e.st.branch_correct),
                std::format("{:.2f}", e.st.BranchAccuracy()),
                std::format("{}", e.st.jal_total),
                std::format("{}", e.st.jal_correct),
                std::format("{:.2f}", e.st.JalAccuracy()),
                std::format("{}", e.st.call_total),
                std::format("{}", e.st.call_correct),
                std::format("{:.2f}", e.st.CallAccuracy()),
                std::format("{}", e.st.ret_total),
                std::format("{}", e.st.ret_correct),
                std::format("{:.2f}", e.st.RetAccuracy()),
                std::format("{}", e.st.jalr_total),
                std::format("{}", e.st.jalr_correct),
                std::format("{:.2f}", e.st.JalrAccuracy())};
        })};
    std::println("DSE 完成，{} 组结果，结果保存在 {}", results.size(), path.string());
}
