export module BTraceReader;
import std;
import BranchRecord;
export class BTraceReader
{
private:
    std::ifstream file;
    std::size_t count{};
    std::optional<BranchRecord> TryParse(std::string_view line);

public:
    BTraceReader() = default;
    ~BTraceReader() = default;
    BTraceReader(const BTraceReader &) = delete;
    BTraceReader &operator=(const BTraceReader &) = delete;
    explicit BTraceReader(const std::string &path);
    std::optional<BranchRecord> next();
    std::size_t GetCount() const;
};