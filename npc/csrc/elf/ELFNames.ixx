export module npc.elf.ELFNames;
import std;
export namespace npc::elf
{
// 排除加载器追加的尾部 NUL。
[[nodiscard]] std::span<const char> original_string_bytes(const std::vector<char> &table);
[[nodiscard]] std::optional<std::string_view> string_at(std::span<const char> table, std::size_t offset);
[[nodiscard]] std::string_view section_name(const std::vector<char> &table, std::uint32_t name_offset) noexcept;
[[nodiscard]] std::string_view symbol_name(const std::vector<char> &table, std::uint32_t name_offset) noexcept;
}
