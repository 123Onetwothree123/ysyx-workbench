export module trace;
import std;
import types;

export class TraceReader {
public:
    explicit TraceReader(const std::string& path);
    ~TraceReader();
    TraceReader(const TraceReader&) = delete;
    TraceReader& operator=(const TraceReader&) = delete;

    auto next() -> std::optional<Addr>;
    [[nodiscard]] auto count() const -> std::size_t { return count_; }

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
    std::size_t count_ = 0;

    auto try_parse(const std::string& line) -> std::optional<Addr>;
};
