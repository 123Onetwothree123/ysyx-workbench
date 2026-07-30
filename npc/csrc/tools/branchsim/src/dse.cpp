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
    // 生成所有参数组合(ways超过组数无意义, 跳过)
    struct Combo { std::size_t bb, bw, jb, jw, rb; };
    std::vector<Combo> configs{};
    for (auto bb : btb_bits_list)
        for (auto bw : btb_ways_list)
        {
            if (bw > (std::size_t{1} << bb)) continue;
            for (auto jb : jal_bits_list)
                for (auto jw : jal_ways_list)
                {
                    if (jw > (std::size_t{1} << jb)) continue;
                    for (auto rb : ras_bits_list)
                        configs.push_back({bb, bw, jb, jw, rb});
                }
        }

    // 结果槽: 每combo固定5个算法(BPAlgorithmsType构造顺序固定)
    constexpr std::size_t AlgosPerCombo{5};
    std::vector<std::array<DseEntry, AlgosPerCombo>> results{configs.size()};

    // 线程池: hardware_concurrency个worker从原子计数器抢任务, 全力跑满
    std::atomic<std::size_t> next{0};
    const auto worker_count{std::min<std::size_t>(
        std::max(1u, std::thread::hardware_concurrency()), configs.size())};
    {
        std::vector<std::jthread> threads{};
        for (std::size_t t{0}; t < worker_count; ++t)
        {
            threads.emplace_back([&]
            {
                for (;;)
                {
                    auto i{next.fetch_add(1, std::memory_order_relaxed)};
                    if (i >= configs.size()) break;
                    BPConfig config{configs[i].bb, configs[i].bw, configs[i].jb, configs[i].jw, configs[i].rb};
                    BPAlgorithmsType algos{config};
                    for (std::size_t k{0}; k < algos.size(); ++k)
                    {
                        auto& e{results[i][k]};
                        e.btb_bits = configs[i].bb;
                        e.btb_ways = configs[i].bw;
                        e.jal_btb_bits = configs[i].jb;
                        e.jal_btb_ways = configs[i].jw;
                        e.ras_bits = configs[i].rb;
                        e.algo = algos[k].GetName();
                        e.st = RunOne(algos[k], trace);
                    }
                }
            });
        }
    }   // jthread 析构自动 join, 确保所有线程执行完毕

    // 展平并按总准确率降序排列
    std::vector<DseEntry> table{};
    table.reserve(configs.size() * AlgosPerCombo);
    for (auto& row : results)
        for (auto& e : row)
            table.push_back(std::move(e));
    std::ranges::sort(table, [](const DseEntry& a, const DseEntry& b)
    {
        return a.st.Accuracy() > b.st.Accuracy();
    });
    return table;
}

void WriteDseCSV(std::span<const DseEntry> results,
                 const std::filesystem::path& base,
                 std::string_view tag)
{
    // 生成结果目录 branchsim/TestResult/时间戳(-trace名)/
    const auto now{std::chrono::system_clock::now()};
    const auto time_t{std::chrono::system_clock::to_time_t(now)};
    const auto tm{*std::localtime(&time_t)};
    auto dirname{std::format("{:04}{:02}{:02}-{:02}{:02}{:02}",
        tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday, tm.tm_hour, tm.tm_min, tm.tm_sec)};
    if (!tag.empty())
        dirname += std::format("-{}", tag);
    const auto dir{base / dirname};
    std::filesystem::create_directories(dir);

    const auto result_path{dir / "result.csv"};
    std::ofstream file{result_path, std::ios::binary};
    file << "\xEF\xBB\xBF"; // UTF-8 BOM, Excel打开不乱码
    file << "分支BTB组数,分支BTB相联度,jalBTB组数,jalBTB相联度,RAS深度,算法,"
            "总分支,正确,误预测,准确率(%),"
            "分支总数,分支正确,分支准确率(%),jal总数,jal正确,jal准确率(%),"
            "call总数,call正确,call准确率(%),ret总数,ret正确,ret准确率(%),"
            "jalr总数,jalr正确,jalr准确率(%)\n";
    for (const auto& e : results)
    {
        file << std::format("{},{},{},{},{},{},{},{},{},{:.2f},{},{},{:.2f},{},{},{:.2f},{},{},{:.2f},{},{},{:.2f},{},{},{:.2f}\n",
                            std::size_t{1} << e.btb_bits, e.btb_ways,
                            std::size_t{1} << e.jal_btb_bits, e.jal_btb_ways,
                            std::size_t{1} << e.ras_bits,
                            e.algo, e.st.total, e.st.correct, e.st.Mispred(), e.st.Accuracy(),
                            e.st.branch_total, e.st.branch_correct, e.st.BranchAccuracy(),
                            e.st.jal_total, e.st.jal_correct, e.st.JalAccuracy(),
                            e.st.call_total, e.st.call_correct, e.st.CallAccuracy(),
                            e.st.ret_total, e.st.ret_correct, e.st.RetAccuracy(),
                            e.st.jalr_total, e.st.jalr_correct, e.st.JalrAccuracy());
    }
    std::println("DSE 完成，{} 组结果，结果保存在 {}", results.size(), result_path.string());
}
