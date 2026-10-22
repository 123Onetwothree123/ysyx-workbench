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
    std::FILE*  file_  = nullptr;
    std::size_t count_ = 0;
    bool   is_pipe_ = false;
    int compressed_child_pid_ = -1;

    auto try_parse(const std::string& line) -> std::optional<Addr>;
    void finish_compressed_input();
};
