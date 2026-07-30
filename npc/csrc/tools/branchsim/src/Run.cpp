module Run;
import std;
import BPAlgorithmsType;
import BPAlgorithmBase;
import BranchRecord;
import stats;

// 按记录类型累计分桶(JalrCall/JalrOther合并为jalr桶)
static void Bump(std::size_t stats::* total, std::size_t stats::* correct,
                 stats& st, bool ok)
{
    ++(st.*total);
    if (ok)
        ++(st.*correct);
}

auto RunOne(BPAlgorithmBase& algo,
            std::span<const BranchRecord> trace) -> stats
{
    stats st{};
    for (const auto& rec : trace)
    {
        auto pred{algo.predict(rec.GetPC())};
        // 与硬件重定向判据一致: 实际下一PC == 预测下一PC 才算对
        // 实际taken: 需预测taken且(目标可信时)目标一致; 实际not-taken: 需预测not-taken
        bool correct;
        if (!rec.GetTaken())
            correct = !pred.taken;
        else
            correct = pred.taken && (!pred.target_known || pred.target == rec.GetTarget());
        ++st.total;
        if (correct)
            ++st.correct;
        switch (rec.GetKind())
        {
        case BranchKind::Branch: Bump(&stats::branch_total, &stats::branch_correct, st, correct); break;
        case BranchKind::Jal:    Bump(&stats::jal_total, &stats::jal_correct, st, correct); break;
        case BranchKind::Call:   Bump(&stats::call_total, &stats::call_correct, st, correct); break;
        case BranchKind::Ret:    Bump(&stats::ret_total, &stats::ret_correct, st, correct); break;
        default:                 Bump(&stats::jalr_total, &stats::jalr_correct, st, correct); break;
        }
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
