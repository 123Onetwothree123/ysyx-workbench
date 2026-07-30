export module dse;
import std;
import BranchRecord;
import stats;

// DSE结果条目: 一组参数组合 × 一个算法的评估结果
export struct DseEntry
{
    std::size_t btb_bits;
    std::size_t btb_ways;
    std::size_t jal_btb_bits;
    std::size_t jal_btb_ways;
    std::size_t ras_bits;
    std::string algo; // GetName()返回的是算法内部string_view, 必须拷贝
    stats st;
};

// 穷举 分支BTB(bits×ways) × jalBTB(bits×ways) × RAS深度 的全部组合,
// 每组配置下顺序跑所有算法, 配置间用线程池并行(hardware_concurrency个worker全力跑)
export auto RunDse(std::span<const BranchRecord> trace,
                   std::span<const std::size_t> btb_bits_list,
                   std::span<const std::size_t> btb_ways_list,
                   std::span<const std::size_t> jal_bits_list,
                   std::span<const std::size_t> jal_ways_list,
                   std::span<const std::size_t> ras_bits_list) -> std::vector<DseEntry>;

export void WriteDseCSV(std::span<const DseEntry> results,
                        const std::filesystem::path& base,
                        std::string_view tag = "");
