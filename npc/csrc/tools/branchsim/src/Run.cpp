module Run;
import std;
import BPAlgorithmsType;
import BPAlgorithmBase;
import BranchRecord;
import stats;

auto RunOne(BPAlgorithmBase& algo,
            std::span<const BranchRecord> trace) -> stats
{
    stats st{};
    for (const auto& rec : trace)
    {
        auto pred{algo.predict(rec.GetPC())};
        if (pred == rec.GetTaken())
        {
            ++st.correct;
            if (rec.GetKind() == BranchKind::Jal)
                ++st.jal_correct;
            else
                ++st.branch_correct;
        }
        ++st.total;
        if (rec.GetKind() == BranchKind::Jal)
            ++st.jal_total;
        else
            ++st.branch_total;
        algo.update(rec.GetPC(), rec.GetTaken(), rec.GetTarget(), rec.GetKind());
    }
    return st;
}

auto Run(const BPAlgorithmsType& algos,
         std::span<const BranchRecord> trace)
    -> std::vector<std::pair<std::string_view, stats>>
{
    const auto N{algos.size()};
    std::vector<stats> results{N};

    {
        std::vector<std::jthread> threads{};
        for (std::size_t i{0}; i < N; ++i)
        {
            threads.emplace_back([&, i]
            {
                results[i] = RunOne(algos[i], trace);
            });
        }
    }   // jthread 析构自动 join, 确保所有线程执行完毕

    std::vector<std::pair<std::string_view, stats>> table{};
    for (std::size_t i{0}; i < N; ++i)
    {
        table.emplace_back(algos[i].GetName(), std::move(results[i]));
    }
    return table;
}
