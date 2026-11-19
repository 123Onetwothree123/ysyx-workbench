module;
#include <elf.h>
module npc.elf.ELFPrinter;
import std;
import npc.unicode;
import npc.elf.ELFFile;
import npc.elf.ELFNames;
import npc.elf.ELFQuery;
namespace
{
constexpr auto AddressWidth{npc::elf::IsELF64 ? 16 : 8};
template <typename... Args>
void print_to(std::ostream &os, std::format_string<Args...> format, Args &&...args)
{
    std::format_to(std::ostreambuf_iterator<char>(os), format, std::forward<Args>(args)...);
}
std::string_view elf_class_name(unsigned char elf_class)
{
    switch (elf_class)
    {
    case ELFCLASSNONE:
    {
        return "none(无)";
    }
    case ELFCLASS32:
    {
        return "ELF32(32位)";
    }
    case ELFCLASS64:
    {
        return "ELF64(64位)";
    }
    default:
    {
        return "unknown(未知)";
    }
    }
}
std::string_view elf_data_name(unsigned char data)
{
    switch (data)
    {
    case ELFDATANONE:
    {
        return "none(无)";
    }
    case ELFDATA2LSB:
    {
        return "2's complement, little endian(二进制补码，小端序)";
    }
    case ELFDATA2MSB:
    {
        return "2's complement, big endian(二进制补码，大端序)";
    }
    default:
    {
        return "unknown(未知)";
    }
    }
}
std::string_view elf_type_name(npc::elf::Half type)
{
    switch (type)
    {
    case ET_NONE:
    {
        return "NONE (None)(无)";
    }
    case ET_REL:
    {
        return "REL (Relocatable file)(可重定位文件)";
    }
    case ET_EXEC:
    {
        return "EXEC (Executable file)(可执行文件)";
    }
    case ET_DYN:
    {
        return "DYN (Shared object file)(共享目标文件)";
    }
    case ET_CORE:
    {
        return "CORE (Core file)(核心转储文件)";
    }
    default:
    {
        return "UNKNOWN(未知)";
    }
    }
}
std::string_view section_type_name(npc::elf::Word type)
{
    switch (type)
    {
    case SHT_NULL:
    {
        return "NULL(空)";
    }
    case SHT_PROGBITS:
    {
        return "PROGBITS(程序数据)";
    }
    case SHT_SYMTAB:
    {
        return "SYMTAB(符号表)";
    }
    case SHT_STRTAB:
    {
        return "STRTAB(字符串表)";
    }
    case SHT_RELA:
    {
        return "RELA(重定位加数)";
    }
    case SHT_HASH:
    {
        return "HASH(哈希表)";
    }
    case SHT_DYNAMIC:
    {
        return "DYNAMIC(动态链接)";
    }
    case SHT_NOTE:
    {
        return "NOTE(备注)";
    }
    case SHT_NOBITS:
    {
        return "NOBITS(无文件数据)";
    }
    case SHT_REL:
    {
        return "REL(重定位)";
    }
    case SHT_SHLIB:
    {
        return "SHLIB(保留)";
    }
    case SHT_DYNSYM:
    {
        return "DYNSYM(动态符号表)";
    }
    case SHT_INIT_ARRAY:
    {
        return "INIT_ARRAY(初始化数组)";
    }
    case SHT_FINI_ARRAY:
    {
        return "FINI_ARRAY(终止数组)";
    }
    case SHT_PREINIT_ARRAY:
    {
        return "PREINIT_ARRAY(预初始化数组)";
    }
    case SHT_GROUP:
    {
        return "GROUP(节组)";
    }
    case SHT_SYMTAB_SHNDX:
    {
        return "SYMTAB SECTION INDICES(符号表节索引)";
    }
#ifdef SHT_GNU_ATTRIBUTES
    case SHT_GNU_ATTRIBUTES:
    {
        return "GNU_ATTRIBUTES(GNU属性)";
    }
#endif
#ifdef SHT_GNU_HASH
    case SHT_GNU_HASH:
    {
        return "GNU_HASH(GNU哈希)";
    }
#endif
#ifdef SHT_GNU_verdef
    case SHT_GNU_verdef:
    {
        return "VERDEF(版本定义)";
    }
#endif
#ifdef SHT_GNU_verneed
    case SHT_GNU_verneed:
    {
        return "VERNEED(版本需求)";
    }
#endif
#ifdef SHT_GNU_versym
    case SHT_GNU_versym:
    {
        return "VERSYM(版本符号)";
    }
#endif
    default:
    {
        return "UNKNOWN(未知)";
    }
    }
}
std::string section_flags(npc::elf::Xword flags)
{
    std::string result;
    const auto append_if{[&](npc::elf::Xword mask, char flag)
                         {
                             if ((flags & mask) != 0)
                             {
                                 result.push_back(flag);
                             }
                         }};
    append_if(SHF_WRITE, 'W');
    append_if(SHF_ALLOC, 'A');
    append_if(SHF_EXECINSTR, 'X');
    append_if(SHF_MERGE, 'M');
    append_if(SHF_STRINGS, 'S');
    append_if(SHF_INFO_LINK, 'I');
    append_if(SHF_LINK_ORDER, 'L');
    append_if(SHF_OS_NONCONFORMING, 'O');
    append_if(SHF_GROUP, 'G');
    append_if(SHF_TLS, 'T');
#ifdef SHF_COMPRESSED
    append_if(SHF_COMPRESSED, 'C');
#endif
#ifdef SHF_EXCLUDE
    append_if(SHF_EXCLUDE, 'E');
#endif
    return result;
}
std::string_view symbol_bind_name(unsigned char info)
{
    switch (npc::elf::symbol_bind(info))
    {
    case STB_LOCAL:
    {
        return "LOCAL(本地)";
    }
    case STB_GLOBAL:
    {
        return "GLOBAL(全局)";
    }
    case STB_WEAK:
    {
        return "WEAK(弱)";
    }
#ifdef STB_GNU_UNIQUE
    case STB_GNU_UNIQUE:
    {
        return "UNIQUE(唯一)";
    }
#endif
    default:
    {
        return "UNKNOWN(未知)";
    }
    }
}
std::string_view symbol_type_name(unsigned char info)
{
    switch (npc::elf::symbol_type(info))
    {
    case STT_NOTYPE:
    {
        return "NOTYPE(无类型)";
    }
    case STT_OBJECT:
    {
        return "OBJECT(对象)";
    }
    case STT_FUNC:
    {
        return "FUNC(函数)";
    }
    case STT_SECTION:
    {
        return "SECTION(节)";
    }
    case STT_FILE:
    {
        return "FILE(文件)";
    }
    case STT_COMMON:
    {
        return "COMMON(公共)";
    }
    case STT_TLS:
    {
        return "TLS(线程局部)";
    }
#ifdef STT_GNU_IFUNC
    case STT_GNU_IFUNC:
    {
        return "IFUNC(间接函数)";
    }
#endif
    default:
    {
        return "UNKNOWN(未知)";
    }
    }
}
std::string_view symbol_visibility_name(unsigned char other)
{
    switch (npc::elf::symbol_visibility(other))
    {
    case STV_DEFAULT:
    {
        return "DEFAULT(默认)";
    }
    case STV_INTERNAL:
    {
        return "INTERNAL(内部)";
    }
    case STV_HIDDEN:
    {
        return "HIDDEN(隐藏)";
    }
    case STV_PROTECTED:
    {
        return "PROTECTED(受保护)";
    }
    default:
    {
        return "UNKNOWN(未知)";
    }
    }
}
std::string symbol_section_index(npc::elf::Half section_index)
{
    if (section_index == SHN_UNDEF)
    {
        return "UND(未定义)";
    }
    if (section_index == SHN_ABS)
    {
        return "ABS(绝对)";
    }
    if (section_index == SHN_COMMON)
    {
        return "COM(公共)";
    }
    return std::to_string(static_cast<unsigned>(section_index));
}
std::string format_hex(std::uint64_t value, int width)
{
    if (width <= 0)
    {
        return std::format("0x{:x}", value);
    }
    return std::format("0x{0:0{1}x}", value, width);
}
void print_header_field(std::ostream &os, std::string_view label, std::string_view value)
{
    constexpr auto LabelWidth{std::size_t{60}};
    print_to(os, "  {} {}\n", pad_right(label, LabelWidth), value);
}
}
namespace npc::elf
{
void print_file_header(std::ostream &os, const Header &header)
{
    print_to(os, "ELF Header:(ELF文件头)\n");
    auto magic{std::string{}};
    for (unsigned char byte : header.e_ident)
    {
        if (!magic.empty())
        {
            magic.push_back(' ');
        }
        magic += std::format("{:02x}", static_cast<unsigned>(byte));
    }
    print_header_field(os, "Magic:(魔数)", magic);
    print_header_field(os, "Class:(类别)", elf_class_name(header.e_ident[EI_CLASS]));
    print_header_field(os, "Data:(数据编码)", elf_data_name(header.e_ident[EI_DATA]));
    print_header_field(os, "Version:(版本)", std::to_string(static_cast<unsigned>(header.e_ident[EI_VERSION])));
    print_header_field(os, "OS/ABI:(操作系统/ABI)", std::to_string(static_cast<unsigned>(header.e_ident[EI_OSABI])));
    print_header_field(os, "ABI Version:(ABI版本)", std::to_string(static_cast<unsigned>(header.e_ident[EI_ABIVERSION])));
    print_header_field(os, "Type:(类型)", elf_type_name(header.e_type));
    print_header_field(os, "Machine:(机器)", std::to_string(header.e_machine));
    print_header_field(os, "Version:(版本)", format_hex(header.e_version, 0));
    print_header_field(os, "Entry point address:(入口点地址)", format_hex(static_cast<std::uint64_t>(header.e_entry), AddressWidth));
    print_header_field(os,
                       "Start of program headers:(程序头起始位置)",
                       std::format("{0} (bytes into file)(文件内字节偏移)", static_cast<unsigned long long>(header.e_phoff)));
    print_header_field(os,
                       "Start of section headers:(节头起始位置)",
                       std::format("{0} (bytes into file)(文件内字节偏移)", static_cast<unsigned long long>(header.e_shoff)));
    print_header_field(os, "Flags:(标志)", format_hex(header.e_flags, 0));
    print_header_field(os, "Size of this header:(本文件头大小)", std::format("{0} (bytes)(字节)", header.e_ehsize));
    print_header_field(os, "Size of program headers:(程序头表项大小)", std::format("{0} (bytes)(字节)", header.e_phentsize));
    print_header_field(os, "Number of program headers:(程序头数量)", std::to_string(header.e_phnum));
    print_header_field(os, "Size of section headers:(节头表项大小)", std::format("{0} (bytes)(字节)", header.e_shentsize));
    print_header_field(os, "Number of section headers:(节头数量)", std::to_string(header.e_shnum));
    print_header_field(os, "Section header string table index:(节头字符串表索引)", std::to_string(header.e_shstrndx));
}
void print_section_headers(std::ostream &os,
                           std::span<const SectionHeader> sections,
                           const std::vector<char> &section_name_table)
{
    print_to(os, "Section Headers:(节头表)\n");
    constexpr auto IndexWidth{std::size_t{10}};
    constexpr auto NameWidth{std::size_t{18}};
    constexpr auto TypeWidth{std::size_t{40}};
    const auto AddressColumnWidth{std::max<std::size_t>(AddressWidth, display_width("Address(地址)"))};
    const auto OffsetWidth{std::max<std::size_t>(6, display_width("Off(偏移)"))};
    const auto SizeWidth{std::max<std::size_t>(6, display_width("Size(大小)"))};
    const auto EntrySizeWidth{std::max<std::size_t>(2, display_width("ES(项大小)"))};
    const auto FlagsWidth{std::max<std::size_t>(3, display_width("Flg(标志)"))};
    const auto LinkWidth{std::max<std::size_t>(2, display_width("Lk(链接)"))};
    const auto InfoWidth{std::max<std::size_t>(3, display_width("Inf(信息)"))};
    const auto AlignWidth{std::max<std::size_t>(2, display_width("Al(对齐)"))};
    print_to(os,
             "  {} {} {} {} {} {} {} {} {} {} {}\n",
             pad_right("[Nr](编号)", IndexWidth),
             pad_right("Name(名称)", NameWidth),
             pad_right("Type(类型)", TypeWidth),
             pad_left("Address(地址)", AddressColumnWidth),
             pad_left("Off(偏移)", OffsetWidth),
             pad_left("Size(大小)", SizeWidth),
             pad_left("ES(项大小)", EntrySizeWidth),
             pad_right("Flg(标志)", FlagsWidth),
             pad_left("Lk(链接)", LinkWidth),
             pad_left("Inf(信息)", InfoWidth),
             pad_left("Al(对齐)", AlignWidth));
    for (std::size_t i{0}; i < sections.size(); ++i)
    {
        const auto &section{sections[i]};
        print_to(os,
                 "  {} {} {} {} {} {} {} {} {} {} {}\n",
                 pad_right(std::format("[{:2}]", i), IndexWidth),
                 pad_right(section_name(section_name_table, section.sh_name), NameWidth),
                 pad_right(section_type_name(section.sh_type), TypeWidth),
                 pad_left(std::format("{0:0{1}x}", static_cast<unsigned long long>(section.sh_addr), AddressWidth), AddressColumnWidth),
                 pad_left(std::format("{:06x}", static_cast<unsigned long long>(section.sh_offset)), OffsetWidth),
                 pad_left(std::format("{:06x}", static_cast<unsigned long long>(section.sh_size)), SizeWidth),
                 pad_left(std::format("{:02x}", static_cast<unsigned long long>(section.sh_entsize)), EntrySizeWidth),
                 pad_right(section_flags(section.sh_flags), FlagsWidth),
                 pad_left(std::to_string(static_cast<unsigned>(section.sh_link)), LinkWidth),
                 pad_left(std::to_string(static_cast<unsigned>(section.sh_info)), InfoWidth),
                 pad_left(std::to_string(static_cast<unsigned long long>(section.sh_addralign)), AlignWidth));
    }
}
void print_symbols(std::ostream &os,
                   std::span<const Symbol> symbols,
                   std::span<const SectionHeader> sections,
                   const std::vector<char> &string_table,
                   const std::vector<char> &section_name_table)
{
    const auto symbol_table_index{symbol_table_section_index(sections)};
    const auto table_name{symbol_table_index >= 0 ? section_name(section_name_table, sections[symbol_table_index].sh_name) : std::string_view("<symtab>(<符号表>)")};
    print_to(os, "Symbol table '{}' contains {} entries:(符号表'{}'包含{}个条目)\n",
             table_name,
             symbols.size(),
             table_name,
             symbols.size());
    constexpr auto NumWidth{std::size_t{10}};
    const auto ValueWidth{std::max<std::size_t>(AddressWidth, display_width("Value(值)"))};
    const auto SizeColumnWidth{std::max<std::size_t>(5, display_width("Size(大小)"))};
    constexpr auto TypeColumnWidth{std::size_t{18}};
    constexpr auto BindColumnWidth{std::size_t{14}};
    constexpr auto VisibilityWidth{std::size_t{18}};
    constexpr auto SectionIndexWidth{std::size_t{12}};
    print_to(os,
             "  {} {} {} {} {} {} {} {}\n",
             pad_left("Num:(编号)", NumWidth),
             pad_left("Value(值)", ValueWidth),
             pad_left("Size(大小)", SizeColumnWidth),
             pad_right("Type(类型)", TypeColumnWidth),
             pad_right("Bind(绑定)", BindColumnWidth),
             pad_right("Vis(可见性)", VisibilityWidth),
             pad_left("Ndx(节索引)", SectionIndexWidth),
             "Name(名称)");
    for (std::size_t i{0}; i < symbols.size(); ++i)
    {
        const auto &symbol{symbols[i]};
        print_to(os,
                 "  {} {} {} {} {} {} {} {}\n",
                 pad_left(std::format("{:4}:", i), NumWidth),
                 pad_left(std::format("{0:0{1}x}", static_cast<unsigned long long>(symbol.st_value), AddressWidth), ValueWidth),
                 pad_left(std::to_string(static_cast<unsigned long long>(symbol.st_size)), SizeColumnWidth),
                 pad_right(symbol_type_name(symbol.st_info), TypeColumnWidth),
                 pad_right(symbol_bind_name(symbol.st_info), BindColumnWidth),
                 pad_right(symbol_visibility_name(symbol.st_other), VisibilityWidth),
                 pad_left(symbol_section_index(symbol.st_shndx), SectionIndexWidth),
                 symbol_name(string_table, symbol.st_name));
    }
}
}
