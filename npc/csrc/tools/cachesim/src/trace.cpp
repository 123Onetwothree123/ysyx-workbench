module trace;
import std;

extern "C" {
    std::FILE* popen(const char*, const char*);
    int pclose(std::FILE*);
}

TraceReader::TraceReader(const std::string& path)
{
    // 支持 bzip2 压缩文件
    if (path.ends_with(".bz2")) {
        std::string cmd = "bzcat " + path;
        file_ = popen(cmd.c_str(), "r");
        is_pipe_ = true;
    } else {
        file_ = std::fopen(path.c_str(), "rb");
        is_pipe_ = false;
    }

    if (!file_)
        throw std::runtime_error("无法打开 trace 文件: " + path);
}

TraceReader::~TraceReader()
{
    if (file_) {
        if (is_pipe_)
            pclose(file_);
        else
            std::fclose(file_);
    }
}

namespace
{
    std::optional<std::uint32_t> ParseNumber(std::string_view text, int base)
    {
        if (text.empty())
        {
            return std::nullopt;
        }
        std::uint32_t value{};
        const auto [ptr, ec]{std::from_chars(text.data(), text.data() + text.size(), value, base)};
        if (ec == std::errc{})
        {
            return value;
        }
        return std::nullopt;
    }
}

auto TraceReader::try_parse(const std::string& line) -> std::optional<std::uint32_t>
{
    if (line.empty())
        return std::nullopt;

    if (line[0] == '#' || line[0] == '/' || line[0] == '\n' || line[0] == '\r')
        return std::nullopt;

    // mtrace 格式: "Memory Read: PC=0x..., Addr=0x..., Data=..."
    if (line.starts_with("Memory Read") || line.starts_with("Memory Write")) {
        auto pos = line.find("Addr=0x");
        if (pos != std::string::npos)
            return ParseNumber(std::string_view{line}.substr(pos + 8), 16);
        pos = line.find("Addr=0X");
        if (pos != std::string::npos)
            return ParseNumber(std::string_view{line}.substr(pos + 8), 16);
        return std::nullopt;
    }

    if (line.starts_with("0x") || line.starts_with("0X"))
        return ParseNumber(std::string_view{line}.substr(2), 16);
    // 原 stoul(..., 0) 的 base 0 自动检测会八进制解释 "0123"
    // from_chars 统一按十进制, 消除了这个坑
    return ParseNumber(line, 10);
}

auto TraceReader::next() -> std::optional<std::uint32_t>
{
    if (!file_)
        return std::nullopt;

    char buf[256];
    while (std::fgets(buf, sizeof(buf), file_)) {
        std::string line(buf);
        while (!line.empty() && (line.back() == '\n' || line.back() == '\r'))
            line.pop_back();

        auto result = try_parse(line);
        if (result) {
            ++count_;
            return result;
        }
    }
    return std::nullopt;
}
