module;
#include <elf.h>
module npc.elf.ELFQuery;
import std;
import npc.trace.ReadelfFunction;
import npc.elf.ELFFile;
import npc.elf.ELFNames;
namespace
{
// ELF32_ST_* 与 ELF64_ST_* 在 glibc 中定义等价，这里只保留一处编译期选择。
template <typename ELF32Operation, typename ELF64Operation>
unsigned ExtractSymbolField(unsigned char byte, ELF32Operation ELF32Function, ELF64Operation ELF64Function) noexcept
{
    if constexpr (npc::elf::IsELF64)
    {
        return ELF64Function(byte);
    }
    else
    {
        return ELF32Function(byte);
    }
}
}
namespace npc::elf
{
int symbol_table_section_index(std::span<const SectionHeader> sections) noexcept
{
    for (std::size_t i{0}; i < sections.size(); ++i)
    {
        if (sections[i].sh_type == SHT_SYMTAB)
        {
            return static_cast<int>(i);
        }
    }
    return -1;
}
unsigned symbol_bind(unsigned char info) noexcept
{
    return ExtractSymbolField(info,
                              [](unsigned char value) noexcept { return ELF32_ST_BIND(value); },
                              [](unsigned char value) noexcept { return ELF64_ST_BIND(value); });
}
unsigned symbol_type(unsigned char info) noexcept
{
    return ExtractSymbolField(info,
                              [](unsigned char value) noexcept { return ELF32_ST_TYPE(value); },
                              [](unsigned char value) noexcept { return ELF64_ST_TYPE(value); });
}
unsigned symbol_visibility(unsigned char other) noexcept
{
    return ExtractSymbolField(other,
                              [](unsigned char value) noexcept { return ELF32_ST_VISIBILITY(value); },
                              [](unsigned char value) noexcept { return ELF64_ST_VISIBILITY(value); });
}
std::vector<ReadelfFunction> build_functions(std::span<const Symbol> symbols, std::span<const char> string_table)
{
    std::vector<ReadelfFunction> functions;
    functions.reserve(symbols.size());
    for (const auto &symbol : symbols)
    {
        if (symbol_type(symbol.st_info) != STT_FUNC ||
            symbol.st_shndx == SHN_UNDEF ||
            symbol.st_size == 0)
        {
            continue;
        }
        const auto name{string_at(string_table, static_cast<std::size_t>(symbol.st_name))};
        if (!name)
        {
            continue;
        }
        const auto start{static_cast<std::size_t>(symbol.st_value)};
        const auto size{static_cast<std::size_t>(symbol.st_size)};
        if (size > std::numeric_limits<std::size_t>::max() - start)
        {
            continue;
        }
        functions.push_back(ReadelfFunction{
            .name = *name,
            .start = start,
            .end = start + size,
        });
    }
    std::ranges::sort(functions, {}, [](const ReadelfFunction &f) { return f.start; });
    return functions;
}
std::optional<ReadelfFunction> find_function(std::span<const ReadelfFunction> functions,
                                             std::size_t address) noexcept
{
    if (functions.empty())
    {
        return std::nullopt;
    }
    const auto it{std::ranges::upper_bound(functions, address, {}, &ReadelfFunction::start)};
    if (it == functions.begin())
    {
        return std::nullopt;
    }
    const ReadelfFunction &candidate{*std::prev(it)};
    if (!candidate.contains(address))
    {
        return std::nullopt;
    }
    return candidate;
}
std::optional<std::string_view> find_function_name(std::span<const ReadelfFunction> functions,
                                                   std::size_t address) noexcept
{
    const auto function{find_function(functions, address)};
    if (!function)
    {
        return std::nullopt;
    }
    return function->name;
}
}
