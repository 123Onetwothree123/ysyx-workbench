export module DTraceReader;
import std;
import AccessRecord;
export class DTraceReader
{
private:
    struct Impl;
    std::unique_ptr<Impl> impl;
    std::size_t count{};
    std::optional<AccessRecord> TryParse(std::string_view line);

public:
    DTraceReader() = default;
    ~DTraceReader();
    DTraceReader(const DTraceReader&) = delete;
    DTraceReader& operator=(const DTraceReader&) = delete;
    explicit DTraceReader(const std::string& path);
    std::optional<AccessRecord> next();
    std::size_t GetCount() const;
};
