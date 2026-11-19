export module npc.elf.ELFQuery;
import std;
import npc.trace.ReadelfFunction;
import npc.elf.ELFFile;
export namespace npc::elf
{
// 第一个 SHT_SYMTAB 的节索引；不存在时返回 -1。
[[nodiscard]] int symbol_table_section_index(std::span<const SectionHeader> sections) noexcept;
[[nodiscard]] unsigned symbol_bind(unsigned char info) noexcept;
[[nodiscard]] unsigned symbol_type(unsigned char info) noexcept;
[[nodiscard]] unsigned symbol_visibility(unsigned char other) noexcept;
// 已定义、非零大小且有名的 STT_FUNC，按起始地址排序。
[[nodiscard]] std::vector<ReadelfFunction> build_functions(std::span<const Symbol> symbols,
                                                           std::span<const char> string_table);
[[nodiscard]] std::optional<ReadelfFunction> find_function(std::span<const ReadelfFunction> functions,
                                                           std::size_t address) noexcept;
[[nodiscard]] std::optional<std::string_view> find_function_name(std::span<const ReadelfFunction> functions,
                                                                 std::size_t address) noexcept;
}
