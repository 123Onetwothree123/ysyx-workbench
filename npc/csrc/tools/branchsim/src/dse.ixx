export module dse;
import std;
import BranchRecord;
import stats;

// 一组参数 × 一个算法的结果。
export struct DseEntry
{
    std::size_t btb_bits;
    std::size_t btb_ways;
    std::size_t jal_btb_bits;
    std::size_t jal_btb_ways;
    std::size_t ras_bits;
    std::string algo; // 必须拷贝 GetName() 的 string_view
    stats st;
};

// 穷举参数组合，配置间并行。
export auto RunDse(std::span<const BranchRecord> trace,
                   std::span<const std::size_t> btb_bits_list,
                   std::span<const std::size_t> btb_ways_list,
                   std::span<const std::size_t> jal_bits_list,
                   std::span<const std::size_t> jal_ways_list,
                   std::span<const std::size_t> ras_bits_list) -> std::vector<DseEntry>;

export void WriteDseCSV(std::span<const DseEntry> results,
                        const std::filesystem::path& base,
                        std::string_view tag = "");
