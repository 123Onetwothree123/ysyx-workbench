module BTraceReader;
import std;
import BranchRecord;
static bool IsComment(std::string_view line)
{
    return line.empty() || line[0] == '#' || line[0] == '/';
}
BTraceReader::BTraceReader(const std::string &path)
{
    file.open(path);
    if (!file)
    {
        throw std::runtime_error(std::format("这个文件无法打开：{}\n", path));
    }
}
std::optional<BranchRecord> BTraceReader::next()
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
std::optional<BranchRecord> BTraceReader::TryParse(std::string_view line)
{
    if (IsComment(line))
    {
        return std::nullopt;
    }
    std::uint32_t pc{}, target{};
    int taken{}; // 这是AI特意改的，说：from_chars 对 int 默认十进制
    const auto end{line.data() + line.size()};
    auto [pointer1, errorcode1] = std::from_chars(line.data(), end, pc, 16);
    if (errorcode1 != std::errc{})
    {
        return std::nullopt;
    }
    while (pointer1 < end && (*pointer1 == ' ' || *pointer1 == '\t'))
    {
        ++pointer1;
    }
    auto [pointer2, errorcode2] = std::from_chars(pointer1, end, target, 16);
    if (errorcode2 != std::errc{})
    {
        return std::nullopt;
    }
    while (pointer2 < end && (*pointer2 == ' ' || *pointer2 == '\t'))
    {
        ++pointer2;
    }
    auto [pointer3, errorcode3] = std::from_chars(pointer2, end, taken);
    if (errorcode3 != std::errc{})
    {
        return std::nullopt;
    }
    // 第4列(可选): 记录类型, 'j'=jal, 其余或缺失=条件分支(兼容旧格式trace)
    auto kind{BranchKind::Branch};
    while (pointer3 < end && (*pointer3 == ' ' || *pointer3 == '\t'))
    {
        ++pointer3;
    }
    if (pointer3 < end && (*pointer3 == 'j' || *pointer3 == 'J'))
    {
        kind = BranchKind::Jal;
    }
    return BranchRecord{pc, target, taken != 0, kind};
}
std::size_t BTraceReader::GetCount() const
{
    return count;
}