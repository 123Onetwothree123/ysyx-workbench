module;
#include <elf.h>
module npc.trace.readelf;
import std;
import npc.trace.ReadelfFunction;
import npc.elf.ELFFile;
import npc.elf.ELFNames;
import npc.elf.ELFQuery;
import npc.elf.ELFPrinter;

Readelf::Readelf() = default;
Readelf::Readelf(Readelf &&) noexcept = default;
Readelf &Readelf::operator=(Readelf &&) noexcept = default;
Readelf::~Readelf() = default;

std::expected<Readelf, std::string> Readelf::load(std::filesystem::path elf_file)
{
    Readelf readelf;
    readelf.path_ = std::move(elf_file);
    auto result{readelf.load_from_file()};
    if (!result)
    {
        return std::unexpected(std::move(result.error()));
    }
    return readelf;
}

bool Readelf::loaded() const noexcept
{
    return loaded_;
}

const std::filesystem::path &Readelf::path() const noexcept
{
    return path_;
}

const Readelf::header_type &Readelf::header() const
{
    return header_;
}

std::span<const Readelf::section_header_type> Readelf::section_headers() const noexcept
{
    return section_headers_;
}

std::span<const Readelf::symbol_type> Readelf::symbols() const noexcept
{
    return symbols_;
}

std::span<const ReadelfFunction> Readelf::functions() const noexcept
{
    return functions_;
}

std::optional<ReadelfFunction> Readelf::find_function(std::size_t address) const noexcept
{
    if (!loaded_)
    {
        return std::nullopt;
    }
    return npc::elf::find_function(functions_, address);
}

std::optional<std::string_view> Readelf::find_function_name(std::size_t address) const noexcept
{
    if (!loaded_)
    {
        return std::nullopt;
    }
    return npc::elf::find_function_name(functions_, address);
}

void Readelf::print_file_header(std::ostream &os) const
{
    if (!loaded_)
    {
        os << "Readelf尚未加载(Readelf is not loaded)\n";
        return;
    }
    npc::elf::print_file_header(os, header_);
}

void Readelf::print_section_headers(std::ostream &os) const
{
    if (!loaded_)
    {
        os << "Readelf尚未加载(Readelf is not loaded)\n";
        return;
    }
    npc::elf::print_section_headers(os, section_headers_, section_name_table_);
}

void Readelf::print_symbols(std::ostream &os) const
{
    if (!loaded_)
    {
        os << "Readelf尚未加载(Readelf is not loaded)\n";
        return;
    }
    npc::elf::print_symbols(os, symbols_, section_headers_, string_table_, section_name_table_);
}

std::expected<void, std::string> Readelf::load_from_file()
{
    loaded_ = false;
    header_ = {};
    section_headers_.clear();
    symbols_.clear();
    string_table_.clear();
    section_name_table_.clear();
    functions_.clear();

    auto file{npc::elf::ELFFile::open(path_)};
    if (!file)
    {
        return std::unexpected(std::move(file.error()));
    }
    auto header{file->read_object_at<header_type>(0, "ELF文件头(ELF header)")};
    if (!header)
    {
        return std::unexpected(std::move(header.error()));
    }
    auto header_check{npc::elf::validate_header(*header)};
    if (!header_check)
    {
        return std::unexpected(std::move(header_check.error()));
    }
    auto sections{npc::elf::read_section_headers(*file, *header)};
    if (!sections)
    {
        return std::unexpected(std::move(sections.error()));
    }
    if (header->e_shstrndx < sections->size() && (*sections)[header->e_shstrndx].sh_type == SHT_STRTAB)
    {
        auto section_names{npc::elf::read_string_table(*file, (*sections)[header->e_shstrndx])};
        if (section_names)
        {
            section_name_table_ = std::move(*section_names);
        }
    }
    const auto SymbolTableIndex{npc::elf::symbol_table_section_index(*sections)};
    if (SymbolTableIndex >= 0)
    {
        const auto &SymbolTable{(*sections)[static_cast<std::size_t>(SymbolTableIndex)]};
        const auto StringTableIndex{static_cast<std::size_t>(SymbolTable.sh_link)};
        if (StringTableIndex >= sections->size() || (*sections)[StringTableIndex].sh_type != SHT_STRTAB)
        {
            return std::unexpected("符号表链接的字符串表无效(symbol table has an invalid linked string table)");
        }
        auto symbols{npc::elf::read_symbols(*file, SymbolTable)};
        if (!symbols)
        {
            return std::unexpected(std::move(symbols.error()));
        }
        auto strings{npc::elf::read_string_table(*file, (*sections)[StringTableIndex])};
        if (!strings)
        {
            return std::unexpected(std::move(strings.error()));
        }
        symbols_ = std::move(*symbols);
        string_table_ = std::move(*strings);
        functions_ = npc::elf::build_functions(symbols_, npc::elf::original_string_bytes(string_table_));
    }
    header_ = *header;
    section_headers_ = std::move(*sections);
    loaded_ = true;
    return {};
}
