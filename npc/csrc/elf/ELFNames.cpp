module npc.elf.ELFNames;
import std;
namespace npc::elf
{
std::span<const char> original_string_bytes(const std::vector<char> &table)
{
    if (table.empty())
    {
        return {};
    }
    return {table.data(), table.size() - 1};
}
std::optional<std::string_view> string_at(std::span<const char> table, std::size_t offset)
{
    if (table.empty() || offset >= table.size())
    {
        return std::nullopt;
    }
    const char *begin{table.data() + offset};
    const auto remaining{table.size() - offset};
    const void *end{std::memchr(begin, '\0', remaining)};
    if (end == nullptr)
    {
        return std::nullopt;
    }
    return std::string_view(begin, static_cast<const char *>(end) - begin);
}
std::string_view section_name(const std::vector<char> &table, std::uint32_t name_offset) noexcept
{
    const auto name{string_at(original_string_bytes(table), static_cast<std::size_t>(name_offset))};
    if (!name)
    {
        return table.empty() ? std::string_view("<no-shstrtab>(<无节名>)") : std::string_view("<bad-name>(<坏名称>)");
    }
    return *name;
}
std::string_view symbol_name(const std::vector<char> &table, std::uint32_t name_offset) noexcept
{
    const auto name{string_at(original_string_bytes(table), static_cast<std::size_t>(name_offset))};
    if (!name)
    {
        return table.empty() ? std::string_view("<no-strtab>(<无串表>)") : std::string_view("<bad-name>(<坏名称>)");
    }
    return *name;
}
}
