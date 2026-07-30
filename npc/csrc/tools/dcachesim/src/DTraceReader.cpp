module DTraceReader;
import std;
import AccessRecord;
static bool IsComment(std::string_view line)
{
    return line.empty() || line[0] == '#' || line[0] == '/';
}
DTraceReader::DTraceReader(const std::string& path)
{
    file.open(path);
    if (!file)
    {
        throw std::runtime_error(std::format("这个文件无法打开：{}\n", path));
    }
}
std::optional<AccessRecord> DTraceReader::next()
{
    std::string line{};
    while (std::getline(file, line))
    {
        if (auto record{TryParse(line)})
        {
            ++count;
            return record;
        }
    }
    return std::nullopt;
}
std::optional<AccessRecord> DTraceReader::TryParse(std::string_view line)
{
    if (IsComment(line))
    {
        return std::nullopt;
    }
    // mtrace 格式: "Memory Read: PC=0x..., Addr=0x..., Data=..."
    if (line.starts_with("Memory Read") || line.starts_with("Memory Write"))
    {
        const auto pos{line.find("Addr=0x")};
        if (pos == std::string_view::npos)
        {
            return std::nullopt;
        }
        std::uint32_t addr{};
        const auto* begin{line.data() + pos + 7};
        const auto end{line.data() + line.size()};
        auto [pointer, errorcode] = std::from_chars(begin, end, addr, 16);
        if (errorcode != std::errc{})
        {
            return std::nullopt;
        }
        return AccessRecord{addr, line.starts_with("Memory Write")};
    }
    // 简单格式: r/w + 空白 + 十六进制地址(不带0x前缀)
    const bool is_write{line[0] == 'w' || line[0] == 'W'};
    if (line[0] != 'r' && line[0] != 'R' && !is_write)
    {
        return std::nullopt;
    }
    const auto end{line.data() + line.size()};
    auto pointer1{line.data() + 1};
    while (pointer1 < end && (*pointer1 == ' ' || *pointer1 == '\t'))
    {
        ++pointer1;
    }
    std::uint32_t addr{};
    auto [pointer2, errorcode2] = std::from_chars(pointer1, end, addr, 16);
    if (errorcode2 != std::errc{})
    {
        return std::nullopt;
    }
    return AccessRecord{addr, is_write};
}
std::size_t DTraceReader::GetCount() const
{
    return count;
}
