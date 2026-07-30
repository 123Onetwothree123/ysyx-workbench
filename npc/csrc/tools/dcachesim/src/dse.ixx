export module dse;
import std;
import AccessRecord;
import stats;

export struct DseEntry
{
    std::size_t block_size{};
    std::size_t num_blocks{};
    std::size_t ways{};
    std::string policy; // 替换策略名字
    stats st;
};

export auto RunDse(std::span<const AccessRecord> trace,
                   std::span<const std::size_t> block_sizes,
                   std::span<const std::size_t> num_blocks_list,
                   std::span<const std::size_t> ways_list) -> std::vector<DseEntry>;

export void WriteDseCSV(std::span<const DseEntry> results, const std::filesystem::path& base);
