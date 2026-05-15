/**
 * @file readelf.cpp
 * @brief 现代 C++23 NPC ELF 阅读器的实现。
 *
 * @details
 * 本实现将解析逻辑限定在本翻译单元内部，仅对外暴露声明于 `readelf.hpp` 中的
 * 只移 `Readelf` 对象。文件 I/O 通过 `std::ifstream` 完成；所有可恢复的错误
 * 均以 `std::expected` 形式返回。原始的 ELF 表在生成任何视图或派生函数记录
 * 之前被复制到 vector 中，因此调用者不会持有指向临时文件缓冲区的指针。
 */
#include "readelf.hpp"

#include <algorithm>
#include <cstring>
#include <format>
#include <fstream>
#include <iterator>
#include <limits>
#include <ostream>
#include <ranges>
#include <utility>

namespace
{
// 本次 NPC 构建所期望的 ELF class 字节。
constexpr unsigned char ExpectedElfClass{Readelf::is_elf64 ? ELFCLASS64 : ELFCLASS32};

// 诊断信息中使用的 `ExpectedElfClass` 的可读形式。
constexpr std::string_view ExpectedElfClassName{Readelf::is_elf64 ? "ELF64" : "ELF32"};

// 打印虚拟地址时使用的十六进制位数。
constexpr int AddressWidth{Readelf::is_elf64 ? 16 : 8};

// 简短的局部别名，使解析器不依赖于预处理器宏。
using Header = Readelf::header_type;
using SectionHeader = Readelf::section_header_type;
using Symbol = Readelf::symbol_type;
using Half = Readelf::half_type;
using Word = Readelf::word_type;
using Xword = Readelf::xword_type;

template <typename... Args>
void print_to(std::ostream &os, std::format_string<Args...> format, Args &&...args)
{
    std::format_to(std::ostreambuf_iterator<char>(os), format, std::forward<Args>(args)...);
}

/**
 * @brief 以更少的调用点噪声构建 `std::expected` 错误值。
 *
 * @param message std::string，人类可读的错误信息。
 * @return std::unexpected<std::string>，包含 `message` 的 `std::unexpected<std::string>`。
 */
std::unexpected<std::string> make_error(std::string message)
{
    return std::unexpected(std::move(message));
}

/**
 * @brief 检查文件偏移量能否用 `std::streamoff` 表示。
 *
 * @param value std::uint64_t，无符号 ELF 文件偏移量。
 * @return bool，若可安全传递给 `std::ifstream::seekg()` 则为 `true`。
 */
bool can_cast_to_streamoff(std::uint64_t value)
{
    return value <= static_cast<std::uint64_t>(std::numeric_limits<std::streamoff>::max());
}

/**
 * @brief 检查字节数能否用 `std::streamsize` 表示。
 *
 * @param value std::size_t，从流请求的字节数。
 * @return bool，若可安全传递给 `std::ifstream::read()` 则为 `true`。
 */
bool can_cast_to_streamsize(std::size_t value)
{
    return value <= static_cast<std::size_t>(std::numeric_limits<std::streamsize>::max());
}

/**
 * @brief 将元素个数与单项大小相乘，并检查溢出。
 *
 * @param count std::size_t，元素个数。
 * @param item_size std::size_t，单个元素的字节大小。
 * @param[out] bytes std::size_t&，成功时接收 `count * item_size`。
 * @return bool，若乘法结果可用 `std::size_t` 表示则为 `true`。
 */
bool checked_byte_count(std::size_t count, std::size_t item_size, std::size_t &bytes)
{
    if (item_size != 0 && count > std::numeric_limits<std::size_t>::max() / item_size)
    {
        return false;
    }
    bytes = count * item_size;
    return true;
}

/**
 * @brief 从文件的绝对偏移处读取精确的字节范围。
 *
 * @param file std::ifstream&，已打开的 ELF 文件流。
 * @param offset std::uint64_t，读取前定位到的绝对文件偏移。
 * @param buffer void*，目标缓冲区。
 * @param bytes std::size_t，必须读取的字节数。
 * @param what std::string_view，正在读取的逻辑 ELF 对象的名称，用于诊断信息。
 * @return std::expected<void, std::string>，空的成功值，或解释定位/读取失败的错误。
 *
 * @details
 * 部分读取被视为错误。这比仅检查流的 fail 位更严格，可避免将截断的表
 * 解释为有效的 ELF 数据。
 */
std::expected<void, std::string> read_exact_at(std::ifstream &file,
                                               std::uint64_t offset,
                                               void *buffer,
                                               std::size_t bytes,
                                               std::string_view what)
{
    if (buffer == nullptr)
    {
        return make_error("internal error: null read buffer");
    }
    if (!can_cast_to_streamoff(offset))
    {
        return make_error(std::string(what) + " offset is too large");
    }
    if (!can_cast_to_streamsize(bytes))
    {
        return make_error(std::string(what) + " size is too large");
    }

    file.clear();
    file.seekg(static_cast<std::streamoff>(offset), std::ios::beg);
    if (!file)
    {
        return make_error("failed to seek to " + std::string(what));
    }

    file.read(static_cast<char *>(buffer), static_cast<std::streamsize>(bytes));
    if (file.gcount() != static_cast<std::streamsize>(bytes))
    {
        return make_error("failed to read complete " + std::string(what));
    }
    return {};
}

/**
 * @brief 从文件中读取一个可平凡复制的 ELF 结构。
 *
 * @tparam T 由 `NPC_ISA64` 选择的原生 ELF 结构类型。
 * @param file std::ifstream&，已打开的 ELF 文件流。
 * @param offset std::uint64_t，该对象的绝对文件偏移。
 * @param what std::string_view，该对象的诊断名称。
 * @return std::expected<T, std::string>，解码后的对象或错误字符串。
 */
template <typename T>
std::expected<T, std::string> read_object_at(std::ifstream &file, std::uint64_t offset, std::string_view what)
{
    T object{};
    auto result{read_exact_at(file, offset, &object, sizeof(T), what)};
    if (!result)
    {
        return make_error(result.error());
    }
    return object;
}

/**
 * @brief 将同质的 ELF 表读入 vector。
 *
 * @tparam T 原生 ELF 表项类型。
 * @param file std::ifstream&，已打开的 ELF 文件流。
 * @param offset std::uint64_t，该表的绝对文件偏移。
 * @param count std::size_t，表中预期的条目数。
 * @param what std::string_view，该表的诊断名称。
 * @return std::expected<std::vector<T>, std::string>，条目组成的 vector，或错误字符串。
 */
template <typename T>
std::expected<std::vector<T>, std::string> read_array_at(std::ifstream &file,
                                                         std::uint64_t offset,
                                                         std::size_t count,
                                                         std::string_view what)
{
    std::size_t bytes{0};
    if (!checked_byte_count(count, sizeof(T), bytes))
    {
        return make_error(std::string(what) + " size overflows size_t");
    }

    std::vector<T> objects(count);
    if (bytes == 0)
    {
        return objects;
    }

    auto result{read_exact_at(file, offset, objects.data(), bytes, what)};
    if (!result)
    {
        return make_error(result.error());
    }
    return objects;
}

/**
 * @brief 在解释其他表之前验证 ELF 头。
 *
 * @param header const Header&，从文件偏移零处读取的头。
 * @return std::expected<void, std::string>，空的成功值，或此 ELF 不受支持的原因。
 *
 * @details
 * 解析器有意仅支持 NPC 所需的常见情况：小端 ELF 文件，且其 32/64 位 class
 * 与构建匹配。条目大小检查可防止对具有不兼容主机结构布局的表进行意外重解释。
 */
std::expected<void, std::string> validate_header(const Header &header)
{
    if (header.e_ident[EI_MAG0] != ELFMAG0 ||
        header.e_ident[EI_MAG1] != ELFMAG1 ||
        header.e_ident[EI_MAG2] != ELFMAG2 ||
        header.e_ident[EI_MAG3] != ELFMAG3)
    {
        return make_error("not an ELF file");
    }
    if (header.e_ident[EI_CLASS] != ExpectedElfClass)
    {
        return make_error("ELF class mismatch: expected " + std::string(ExpectedElfClassName));
    }
    if (header.e_ident[EI_DATA] != ELFDATA2LSB)
    {
        return make_error("only little-endian ELF files are supported");
    }
    if (header.e_ident[EI_VERSION] != EV_CURRENT || header.e_version != EV_CURRENT)
    {
        return make_error("unsupported ELF version");
    }
    if (header.e_ehsize != sizeof(Header))
    {
        return make_error("ELF header size mismatch");
    }
    if (header.e_shoff != 0 && header.e_shentsize != sizeof(SectionHeader))
    {
        return make_error("section header entry size mismatch");
    }
    if (header.e_phoff != 0 && header.e_phnum != 0 && header.e_phentsize != sizeof(Readelf::program_header_type))
    {
        return make_error("program header entry size mismatch");
    }
    return {};
}

/**
 * @brief 读取完整的节头表。
 *
 * @param file std::ifstream&，已打开的 ELF 文件流。
 * @param header const Header&，已验证的 ELF 头。
 * @return std::expected<std::vector<SectionHeader>, std::string>，节头条目；若 ELF 没有常规表则返回错误。
 */
std::expected<std::vector<SectionHeader>, std::string> read_section_headers(std::ifstream &file, const Header &header)
{
    if (header.e_shoff == 0 || header.e_shnum == 0)
    {
        return make_error("ELF file has no regular section header table");
    }
    return read_array_at<SectionHeader>(file,
                                        static_cast<std::uint64_t>(header.e_shoff),
                                        static_cast<std::size_t>(header.e_shnum),
                                        "section header table");
}

/**
 * @brief 读取常规的 `SHT_SYMTAB` 节。
 *
 * @param file std::ifstream&，已打开的 ELF 文件流。
 * @param section const SectionHeader&，描述符号表的节头。
 * @return std::expected<std::vector<Symbol>, std::string>，原始符号条目；若该节格式错误则返回错误。
 */
std::expected<std::vector<Symbol>, std::string> read_symbols(std::ifstream &file, const SectionHeader &section)
{
    if (section.sh_type != SHT_SYMTAB)
    {
        return make_error("section is not SHT_SYMTAB");
    }
    if (section.sh_offset == 0 || section.sh_size == 0 || section.sh_entsize == 0)
    {
        return make_error("symbol table section is empty or invalid");
    }
    if (section.sh_entsize != sizeof(Symbol) || section.sh_size % section.sh_entsize != 0)
    {
        return make_error("symbol table entry size mismatch");
    }

    return read_array_at<Symbol>(file,
                                 static_cast<std::uint64_t>(section.sh_offset),
                                 static_cast<std::size_t>(section.sh_size / section.sh_entsize),
                                 "symbol table");
}

/**
 * @brief 读取字符串表并追加一个防御性的尾部 NUL 字节。
 *
 * @param file std::ifstream&，已打开的 ELF 文件流。
 * @param section const SectionHeader&，描述 `SHT_STRTAB` 节的节头。
 * @return std::expected<std::vector<char>, std::string>，字符串表字节加一个额外的 NUL，或错误字符串。
 *
 * @details
 * ELF 字符串表应包含位于节负载内的 NUL 终止字符串。额外的终止符不计入
 * 原始文件数据；它仅在验证后使意外显示最后一个字符串更安全。
 */
std::expected<std::vector<char>, std::string> read_string_table(std::ifstream &file, const SectionHeader &section)
{
    if (section.sh_type != SHT_STRTAB)
    {
        return make_error("section is not SHT_STRTAB");
    }
    if (section.sh_size == 0)
    {
        return make_error("string table is empty");
    }
    if (section.sh_size > static_cast<decltype(section.sh_size)>(std::numeric_limits<std::size_t>::max() - 1))
    {
        return make_error("string table is too large");
    }

    const auto payload_size{static_cast<std::size_t>(section.sh_size)};
    std::vector<char> table(payload_size + 1, '\0');
    auto result{read_exact_at(file,
                                static_cast<std::uint64_t>(section.sh_offset),
                                table.data(),
                                payload_size,
                                "string table")};
    if (!result)
    {
        return make_error(result.error());
    }
    return table;
}

/**
 * @brief 仅返回来自 ELF 字符串表节的字节。
 *
 * @param table const std::vector<char>&，缓存的表，包含加载器额外添加的尾部 NUL 字节。
 * @return std::span<const char>，排除合成尾部 NUL 的 span。
 */
std::span<const char> original_string_bytes(const std::vector<char> &table)
{
    if (table.empty())
    {
        return {};
    }
    return {table.data(), table.size() - 1};
}

/**
 * @brief 将字符串表偏移解析为有界的 `std::string_view`。
 *
 * @param table std::span<const char>，原始字符串表字节。
 * @param offset std::size_t，`table` 内的字节偏移。
 * @return std::optional<std::string_view>，NUL 终止字符串的视图；若偏移无效或字符串未在原始字节内终止，
 *         则返回 `std::nullopt`。
 */
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

/**
 * @brief 从 ELF 符号的 `st_info` 字段中提取绑定位。
 *
 * @param info unsigned char，原始 `st_info` 字节。
 * @return unsigned，`STB_*` 绑定值。
 */
unsigned symbol_bind(unsigned char info)
{
    if constexpr (Readelf::is_elf64)
    {
        return ELF64_ST_BIND(info);
    }
    else
    {
        return ELF32_ST_BIND(info);
    }
}

/**
 * @brief 从 ELF 符号的 `st_info` 字段中提取类型位。
 *
 * @param info unsigned char，原始 `st_info` 字节。
 * @return unsigned，`STT_*` 符号类型值。
 */
unsigned symbol_type(unsigned char info)
{
    if constexpr (Readelf::is_elf64)
    {
        return ELF64_ST_TYPE(info);
    }
    else
    {
        return ELF32_ST_TYPE(info);
    }
}

/**
 * @brief 从 ELF 符号的 `st_other` 字段中提取可见性位。
 *
 * @param other unsigned char，原始 `st_other` 字节。
 * @return unsigned，`STV_*` 可见性值。
 */
unsigned symbol_visibility(unsigned char other)
{
    if constexpr (Readelf::is_elf64)
    {
        return ELF64_ST_VISIBILITY(other);
    }
    else
    {
        return ELF32_ST_VISIBILITY(other);
    }
}

/**
 * @brief 为所有可用的函数符号构建排序后的地址范围。
 *
 * @param symbols std::span<const Symbol>，来自 `SHT_SYMTAB` 的原始条目。
 * @param string_table std::span<const char>，符号字符串表负载。
 * @return std::vector<ReadelfFunction>，按起始地址排序的函数记录。
 *
 * @details
 * 过滤器保留已定义的、大小非零且名称有效的 `STT_FUNC` 符号。跳过大小为零
 * 的函数，因为它们无法构成有意义的半开地址范围以供追踪。
 */
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

    std::ranges::sort(functions, {}, &ReadelfFunction::start);
    return functions;
}

/**
 * @brief 将 ELF class 字节转换为显示字符串。
 *
 * @param elf_class unsigned char，`EI_CLASS` 值。
 * @return std::string_view，用于类似 readelf 输出的稳定字符串字面量。
 */
std::string_view elf_class_name(unsigned char elf_class)
{
    switch (elf_class)
    {
    case ELFCLASSNONE:
        return "none";
    case ELFCLASS32:
        return "ELF32";
    case ELFCLASS64:
        return "ELF64";
    default:
        return "unknown";
    }
}

/**
 * @brief 将 ELF 端序编码字节转换为显示字符串。
 *
 * @param data unsigned char，`EI_DATA` 值。
 * @return std::string_view，用于类似 readelf 输出的稳定字符串字面量。
 */
std::string_view elf_data_name(unsigned char data)
{
    switch (data)
    {
    case ELFDATANONE:
        return "none";
    case ELFDATA2LSB:
        return "2's complement, little endian";
    case ELFDATA2MSB:
        return "2's complement, big endian";
    default:
        return "unknown";
    }
}

/**
 * @brief 将 ELF 文件类型值转换为显示字符串。
 *
 * @param type Half，原始 `e_type` 值。
 * @return std::string_view，用于类似 readelf 输出的稳定字符串字面量。
 */
std::string_view elf_type_name(Half type)
{
    switch (type)
    {
    case ET_NONE:
        return "NONE (None)";
    case ET_REL:
        return "REL (Relocatable file)";
    case ET_EXEC:
        return "EXEC (Executable file)";
    case ET_DYN:
        return "DYN (Shared object file)";
    case ET_CORE:
        return "CORE (Core file)";
    default:
        return "UNKNOWN";
    }
}

/**
 * @brief 将 ELF 节类型值转换为紧凑的显示字符串。
 *
 * @param type Word，原始 `sh_type` 值。
 * @return std::string_view，用于类似 readelf 输出的稳定字符串字面量。
 */
std::string_view section_type_name(Word type)
{
    switch (type)
    {
    case SHT_NULL:
        return "NULL";
    case SHT_PROGBITS:
        return "PROGBITS";
    case SHT_SYMTAB:
        return "SYMTAB";
    case SHT_STRTAB:
        return "STRTAB";
    case SHT_RELA:
        return "RELA";
    case SHT_HASH:
        return "HASH";
    case SHT_DYNAMIC:
        return "DYNAMIC";
    case SHT_NOTE:
        return "NOTE";
    case SHT_NOBITS:
        return "NOBITS";
    case SHT_REL:
        return "REL";
    case SHT_SHLIB:
        return "SHLIB";
    case SHT_DYNSYM:
        return "DYNSYM";
    case SHT_INIT_ARRAY:
        return "INIT_ARRAY";
    case SHT_FINI_ARRAY:
        return "FINI_ARRAY";
    case SHT_PREINIT_ARRAY:
        return "PREINIT_ARRAY";
    case SHT_GROUP:
        return "GROUP";
    case SHT_SYMTAB_SHNDX:
        return "SYMTAB SECTION INDICES";
#ifdef SHT_GNU_ATTRIBUTES
    case SHT_GNU_ATTRIBUTES:
        return "GNU_ATTRIBUTES";
#endif
#ifdef SHT_GNU_HASH
    case SHT_GNU_HASH:
        return "GNU_HASH";
#endif
#ifdef SHT_GNU_verdef
    case SHT_GNU_verdef:
        return "VERDEF";
#endif
#ifdef SHT_GNU_verneed
    case SHT_GNU_verneed:
        return "VERNEED";
#endif
#ifdef SHT_GNU_versym
    case SHT_GNU_versym:
        return "VERSYM";
#endif
    default:
        return "UNKNOWN";
    }
}

/**
 * @brief 使用 GNU readelf 风格的短字母渲染节标志位。
 *
 * @param flags Xword，原始 `sh_flags` 位集合。
 * @return std::string，如 `WA`、`AX` 或 `MS` 的紧凑字符串。
 */
std::string section_flags(Xword flags)
{
    std::string result;
    const auto append_if{[&](Xword mask, char flag) {
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

/**
 * @brief 将符号绑定转换为显示字符串。
 *
 * @param info unsigned char，原始 `st_info` 字节。
 * @return std::string_view，用于类似 readelf 输出的稳定字符串字面量。
 */
std::string_view symbol_bind_name(unsigned char info)
{
    switch (symbol_bind(info))
    {
    case STB_LOCAL:
        return "LOCAL";
    case STB_GLOBAL:
        return "GLOBAL";
    case STB_WEAK:
        return "WEAK";
#ifdef STB_GNU_UNIQUE
    case STB_GNU_UNIQUE:
        return "UNIQUE";
#endif
    default:
        return "UNKNOWN";
    }
}

/**
 * @brief 将符号类型转换为显示字符串。
 *
 * @param info unsigned char，原始 `st_info` 字节。
 * @return std::string_view，用于类似 readelf 输出的稳定字符串字面量。
 */
std::string_view symbol_type_name(unsigned char info)
{
    switch (symbol_type(info))
    {
    case STT_NOTYPE:
        return "NOTYPE";
    case STT_OBJECT:
        return "OBJECT";
    case STT_FUNC:
        return "FUNC";
    case STT_SECTION:
        return "SECTION";
    case STT_FILE:
        return "FILE";
    case STT_COMMON:
        return "COMMON";
    case STT_TLS:
        return "TLS";
#ifdef STT_GNU_IFUNC
    case STT_GNU_IFUNC:
        return "IFUNC";
#endif
    default:
        return "UNKNOWN";
    }
}

/**
 * @brief 将符号可见性值转换为显示字符串。
 *
 * @param other unsigned char，原始 `st_other` 字节。
 * @return std::string_view，用于类似 readelf 输出的稳定字符串字面量。
 */
std::string_view symbol_visibility_name(unsigned char other)
{
    switch (symbol_visibility(other))
    {
    case STV_DEFAULT:
        return "DEFAULT";
    case STV_INTERNAL:
        return "INTERNAL";
    case STV_HIDDEN:
        return "HIDDEN";
    case STV_PROTECTED:
        return "PROTECTED";
    default:
        return "UNKNOWN";
    }
}

/**
 * @brief 格式化符号节索引以供显示。
 *
 * @param section_index Half，原始 `st_shndx` 值。
 * @return std::string，`UND`、`ABS`、`COM` 或十进制节索引。
 */
std::string symbol_section_index(Half section_index)
{
    if (section_index == SHN_UNDEF)
    {
        return "UND";
    }
    if (section_index == SHN_ABS)
    {
        return "ABS";
    }
    if (section_index == SHN_COMMON)
    {
        return "COM";
    }
    return std::to_string(static_cast<unsigned>(section_index));
}

/**
 * @brief 裁剪字符串视图以适应固定宽度的表格列。
 *
 * @param text std::string_view，输入文本。
 * @param max_size std::size_t，最多保留的字节数。
 * @return std::string_view，最多包含 `max_size` 字节的字符串视图。
 */
std::string_view clipped(std::string_view text, std::size_t max_size)
{
    return text.substr(0, max_size);
}

std::string format_hex(std::uint64_t value, int width)
{
    if (width <= 0)
    {
        return std::format("0x{:x}", value);
    }
    return std::format("0x{:0{}x}", value, width);
}
} // namespace

Readelf::Readelf() = default;

Readelf::Readelf(Readelf &&) noexcept = default;

Readelf &Readelf::operator=(Readelf &&) noexcept = default;

Readelf::~Readelf() = default;

/**
 * @brief 从路径创建已加载的 `Readelf` 实例。
 *
 * @param elf_file std::filesystem::path，要解析的文件路径。
 * @return std::expected<Readelf, std::string>，已加载的阅读器，或描述性错误字符串。
 */
std::expected<Readelf, std::string> Readelf::load(std::filesystem::path elf_file)
{
    Readelf readelf;
    readelf.path_ = std::move(elf_file);

    auto result{readelf.load_from_file()};
    if (!result)
    {
        return make_error(result.error());
    }
    return readelf;
}

/**
 * @brief 报告此对象当前是否拥有已解析的 ELF 数据。
 *
 * @return bool，成功加载后返回 `true`。
 */
bool Readelf::loaded() const noexcept
{
    return loaded_;
}

/**
 * @brief 返回用于加载的源路径。
 *
 * @return const std::filesystem::path&，存储路径的引用。
 */
const std::filesystem::path &Readelf::path() const noexcept
{
    return path_;
}

/**
 * @brief 返回缓存的 ELF 头。
 *
 * @return const Readelf::header_type&，已解析头部的引用。
 */
const Readelf::header_type &Readelf::header() const
{
    return header_;
}

/**
 * @brief 返回缓存的节头表。
 *
 * @return std::span<const Readelf::section_header_type>，覆盖所有节头的 span。
 */
std::span<const Readelf::section_header_type> Readelf::section_headers() const noexcept
{
    return section_headers_;
}

/**
 * @brief 返回缓存的原始符号表。
 *
 * @return std::span<const Readelf::symbol_type>，覆盖 `SHT_SYMTAB` 条目的 span；若不存在常规符号表则为空 span。
 */
std::span<const Readelf::symbol_type> Readelf::symbols() const noexcept
{
    return symbols_;
}

/**
 * @brief 返回派生的函数记录。
 *
 * @return std::span<const ReadelfFunction>，按起始地址排序的函数符号 span。
 */
std::span<const ReadelfFunction> Readelf::functions() const noexcept
{
    return functions_;
}

/**
 * @brief 查找其半开范围包含 `address` 的函数。
 *
 * @param address std::size_t，要解析的虚拟地址。
 * @return std::optional<ReadelfFunction>，命中时返回函数记录，否则返回 `std::nullopt`。
 *
 * @details
 * 函数记录按起始地址排序，因此查找时先定位起始不大于 `address` 的最后一个
 * 函数，再验证结束边界。这样实现简单，且比完整的线性扫描更快。
 */
std::optional<ReadelfFunction> Readelf::find_function(std::size_t address) const noexcept
{
    if (!loaded_ || functions_.empty())
    {
        return std::nullopt;
    }

    const auto it{std::ranges::upper_bound(functions_, address, {}, &ReadelfFunction::start)};
    if (it == functions_.begin())
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

/**
 * @brief 仅解析虚拟地址对应的函数名称。
 *
 * @param address std::size_t，要解析的虚拟地址。
 * @return std::optional<std::string_view>，命中时返回名称视图，否则返回 `std::nullopt`。
 */
std::optional<std::string_view> Readelf::find_function_name(std::size_t address) const noexcept
{
    const auto function{find_function(address)};
    if (!function)
    {
        return std::nullopt;
    }
    return function->name;
}

/**
 * @brief 打印类似 readelf 的 ELF 头摘要。
 *
 * @param os std::ostream&，目标流。
 */
void Readelf::print_file_header(std::ostream &os) const
{
    if (!loaded_)
    {
        print_to(os, "Readelf is not loaded\n");
        return;
    }

    print_to(os, "ELF Header:\n");
    print_to(os, "  Magic:  ");
    for (unsigned char byte : header_.e_ident)
    {
        print_to(os, " {:02x}", static_cast<unsigned>(byte));
    }
    print_to(os, "\n");
    print_to(os, "  Class:                             {}\n", elf_class_name(header_.e_ident[EI_CLASS]));
    print_to(os, "  Data:                              {}\n", elf_data_name(header_.e_ident[EI_DATA]));
    print_to(os, "  Version:                           {}\n", static_cast<unsigned>(header_.e_ident[EI_VERSION]));
    print_to(os, "  OS/ABI:                            {}\n", static_cast<unsigned>(header_.e_ident[EI_OSABI]));
    print_to(os, "  ABI Version:                       {}\n", static_cast<unsigned>(header_.e_ident[EI_ABIVERSION]));
    print_to(os, "  Type:                              {}\n", elf_type_name(header_.e_type));
    print_to(os, "  Machine:                           {}\n", header_.e_machine);
    print_to(os, "  Version:                           {}\n", format_hex(header_.e_version, 0));
    print_to(os, "  Entry point address:               {}\n", format_hex(static_cast<std::uint64_t>(header_.e_entry), AddressWidth));
    print_to(os, "  Start of program headers:          {} (bytes into file)\n", static_cast<unsigned long long>(header_.e_phoff));
    print_to(os, "  Start of section headers:          {} (bytes into file)\n", static_cast<unsigned long long>(header_.e_shoff));
    print_to(os, "  Flags:                             {}\n", format_hex(header_.e_flags, 0));
    print_to(os, "  Size of this header:               {} (bytes)\n", header_.e_ehsize);
    print_to(os, "  Size of program headers:           {} (bytes)\n", header_.e_phentsize);
    print_to(os, "  Number of program headers:         {}\n", header_.e_phnum);
    print_to(os, "  Size of section headers:           {} (bytes)\n", header_.e_shentsize);
    print_to(os, "  Number of section headers:         {}\n", header_.e_shnum);
    print_to(os, "  Section header string table index: {}\n", header_.e_shstrndx);
}

/**
 * @brief 打印类似 readelf 的节头表。
 *
 * @param os std::ostream&，目标流。
 */
void Readelf::print_section_headers(std::ostream &os) const
{
    if (!loaded_)
    {
        print_to(os, "Readelf is not loaded\n");
        return;
    }

    print_to(os, "Section Headers:\n");
    print_to(os,
             "  [Nr] Name              Type            Address{}Off    Size   ES Flg Lk Inf Al\n",
             std::string(Readelf::is_elf64 ? 9 : 1, ' '));

    for (std::size_t i{0}; i < section_headers_.size(); ++i)
    {
        const auto &section{section_headers_[i]};

        print_to(os,
                 "  [{:2}] {:<17} {:<15} {:0{}x} {:06x} {:06x} {:02x} {:<3} {:2} {:3} {:2}\n",
                 i,
                 clipped(section_name(section.sh_name), 17),
                 clipped(section_type_name(section.sh_type), 15),
                 static_cast<unsigned long long>(section.sh_addr),
                 AddressWidth,
                 static_cast<unsigned long long>(section.sh_offset),
                 static_cast<unsigned long long>(section.sh_size),
                 static_cast<unsigned long long>(section.sh_entsize),
                 section_flags(section.sh_flags),
                 static_cast<unsigned>(section.sh_link),
                 static_cast<unsigned>(section.sh_info),
                 static_cast<unsigned long long>(section.sh_addralign));
    }
}

/**
 * @brief 打印类似 readelf 的原始符号表。
 *
 * @param os std::ostream&，目标流。
 */
void Readelf::print_symbols(std::ostream &os) const
{
    if (!loaded_)
    {
        print_to(os, "Readelf is not loaded\n");
        return;
    }

    const int symbol_table_index{symbol_table_section_index()};
    const auto table_name{symbol_table_index >= 0 ? section_name(section_headers_[symbol_table_index].sh_name) : std::string_view("<symtab>")};

    print_to(os, "Symbol table '{}' contains {} entries:\n", table_name, symbols_.size());
    print_to(os, "   Num: {:>{}} Size  Type    Bind   Vis      Ndx Name\n", "Value", AddressWidth);

    for (std::size_t i{0}; i < symbols_.size(); ++i)
    {
        const auto &symbol{symbols_[i]};

        print_to(os,
                 "  {:4}: {:0{}x} {:5} {:<7} {:<6} {:<8} {:>3} {}\n",
                 i,
                 static_cast<unsigned long long>(symbol.st_value),
                 AddressWidth,
                 static_cast<unsigned long long>(symbol.st_size),
                 symbol_type_name(symbol.st_info),
                 symbol_bind_name(symbol.st_info),
                 symbol_visibility_name(symbol.st_other),
                 symbol_section_index(symbol.st_shndx),
                 symbol_name(symbol.st_name));
    }
}

/**
 * @brief 从 `path_` 填充此对象。
 *
 * @return std::expected<void, std::string>，空的成功值，或描述性的读取/解析错误。
 *
 * @details
 * 此方法首先重置所有缓存状态。在验证 ELF 头和节头之后，它会择机读取
 * `.shstrtab` 以获得更好的节名打印效果。缺少常规符号表不是致命错误：
 * 该对象仍可打印头部，但 `symbols()` 和 `functions()` 将为空。
 */
std::expected<void, std::string> Readelf::load_from_file()
{
    loaded_ = false;
    header_ = {};
    section_headers_.clear();
    symbols_.clear();
    string_table_.clear();
    section_name_table_.clear();
    functions_.clear();

    std::ifstream file(path_, std::ios::binary);
    if (!file)
    {
        return make_error("failed to open ELF file: " + path_.string());
    }

    auto header{read_object_at<Header>(file, 0, "ELF header")};
    if (!header)
    {
        return make_error(header.error());
    }

    auto header_check{validate_header(*header)};
    if (!header_check)
    {
        return make_error(header_check.error());
    }

    auto sections{read_section_headers(file, *header)};
    if (!sections)
    {
        return make_error(sections.error());
    }

    if (header->e_shstrndx < sections->size() && (*sections)[header->e_shstrndx].sh_type == SHT_STRTAB)
    {
        auto section_names{read_string_table(file, (*sections)[header->e_shstrndx])};
        if (section_names)
        {
            section_name_table_ = std::move(*section_names);
        }
    }

    const auto symbol_table{std::ranges::find_if(*sections, [](const SectionHeader &section) {
        return section.sh_type == SHT_SYMTAB;
    })};

    if (symbol_table != sections->end())
    {
        const auto string_table_index{static_cast<std::size_t>(symbol_table->sh_link)};
        if (string_table_index >= sections->size() || (*sections)[string_table_index].sh_type != SHT_STRTAB)
        {
            return make_error("symbol table has an invalid linked string table");
        }

        auto symbols{read_symbols(file, *symbol_table)};
        if (!symbols)
        {
            return make_error(symbols.error());
        }

        auto strings{read_string_table(file, (*sections)[string_table_index])};
        if (!strings)
        {
            return make_error(strings.error());
        }

        symbols_ = std::move(*symbols);
        string_table_ = std::move(*strings);
        functions_ = build_functions(symbols_, original_string_bytes(string_table_));
    }

    header_ = *header;
    section_headers_ = std::move(*sections);
    loaded_ = true;
    return {};
}

/**
 * @brief 从 `.shstrtab` 解析节名。
 *
 * @param name_offset word_type，`sh_name` 中存储的字节偏移。
 * @return std::string_view，节名视图；数据缺失或错误时返回稳定的占位符。
 */
std::string_view Readelf::section_name(word_type name_offset) const noexcept
{
    const auto name{string_at(original_string_bytes(section_name_table_), static_cast<std::size_t>(name_offset))};
    if (!name)
    {
        return section_name_table_.empty() ? std::string_view("<no-shstrtab>") : std::string_view("<bad-name>");
    }
    return *name;
}

/**
 * @brief 从符号字符串表解析符号名称。
 *
 * @param name_offset word_type，`st_name` 中存储的字节偏移。
 * @return std::string_view，符号名称视图；数据缺失或错误时返回稳定的占位符。
 */
std::string_view Readelf::symbol_name(word_type name_offset) const noexcept
{
    const auto name{string_at(original_string_bytes(string_table_), static_cast<std::size_t>(name_offset))};
    if (!name)
    {
        return string_table_.empty() ? std::string_view("<no-strtab>") : std::string_view("<bad-name>");
    }
    return *name;
}

/**
 * @brief 定位第一个常规符号表节。
 *
 * @return int，从零开始的节索引；若不存在则返回 `-1`。
 */
int Readelf::symbol_table_section_index() const noexcept
{
    for (std::size_t i{0}; i < section_headers_.size(); ++i)
    {
        if (section_headers_[i].sh_type == SHT_SYMTAB)
        {
            return static_cast<int>(i);
        }
    }
    return -1;
}
