module npc.elf.ELFNames;
import std;
namespace
{
// 空表占位符由调用方决定，坏名称的占位符两者一致。
std::string_view NameOrPlaceholder(const std::vector<char> &table,
                                   std::uint32_t NameOffset,
                                   std::string_view EmptyPlaceholder) noexcept
{
    const auto name{npc::elf::string_at(npc::elf::original_string_bytes(table), static_cast<std::size_t>(NameOffset))};
    if (!name)
    {
        return table.empty() ? EmptyPlaceholder : std::string_view("<bad-name>(<坏名称>)");
    }
    return *name;
}
}
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
    return NameOrPlaceholder(table, name_offset, "<no-shstrtab>(<无节名>)");
}
std::string_view symbol_name(const std::vector<char> &table, std::uint32_t name_offset) noexcept
{
    return NameOrPlaceholder(table, name_offset, "<no-strtab>(<无串表>)");
}
}
