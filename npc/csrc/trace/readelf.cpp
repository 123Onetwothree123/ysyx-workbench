module;
#include <elf.h>
module npc.trace.readelf;
/**
 * @file readelf.cpp
 * @brief 现代C++23 NPC ELF阅读器的实现。
 * @details
 * 本实现将解析逻辑限定在本翻译单元内部，仅对外暴露声明于`readelf.hpp`中的
 * 只移`Readelf`对象。文件I/O通过`std::ifstream`完成；所有可恢复的错误
 * 均以`std::expected`形式返回。原始的ELF表在生成任何视图或派生函数记录
 * 之前被复制到vector中，因此调用者不会持有指向临时文件缓冲区的指针。
 */
namespace
{
    constexpr unsigned char ExpectedElfClass{Readelf::is_elf64 ? ELFCLASS64 : ELFCLASS32};
    constexpr auto ExpectedElfClassName{std::string_view{Readelf::is_elf64 ? "ELF64(64位)" : "ELF32(32位)"}};
    constexpr auto AddressWidth{Readelf::is_elf64 ? 16 : 8};
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
     * @brief 以更少的调用点噪声构建`std::expected`错误值。
     * @param message std::string，人类可读的错误信息。
     * @return std::unexpected<std::string>，包含`message`的`std::unexpected<std::string>`。
     */
    std::unexpected<std::string> make_error(std::string message)
    {
        return std::unexpected(std::move(message));
    }
    /**
     * @brief 检查文件偏移量能否用`std::streamoff`表示。
     * @param value std::uint64_t，无符号ELF文件偏移量。
     * @return bool，若可安全传递给`std::ifstream::seekg()`则为`true`。
     */
    bool can_cast_to_streamoff(std::uint64_t value)
    {
        return value <= static_cast<std::uint64_t>(std::numeric_limits<std::streamoff>::max());
    }
    /**
     * @brief 检查字节数能否用`std::streamsize`表示。
     * @param value std::size_t，从流请求的字节数。
     * @return bool，若可安全传递给`std::ifstream::read()`则为`true`。
     */
    bool can_cast_to_streamsize(std::size_t value)
    {
        return value <= static_cast<std::size_t>(std::numeric_limits<std::streamsize>::max());
    }
    /**
     * @brief 将元素个数与单项大小相乘，并检查溢出。
     * @param count std::size_t，元素个数。
     * @param item_size std::size_t，单个元素的字节大小。
     * @param[out] bytes std::size_t&，成功时接收`count * item_size`。
     * @return bool，若乘法结果可用`std::size_t`表示则为`true`。
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
     * @param file std::ifstream&，已打开的ELF文件流。
     * @param offset std::uint64_t，读取前定位到的绝对文件偏移。
     * @param buffer void*，目标缓冲区。
     * @param bytes std::size_t，必须读取的字节数。
     * @param what std::string_view，正在读取的逻辑ELF对象的名称，用于诊断信息。
     * @return std::expected<void, std::string>，空的成功值，或解释定位/读取失败的错误。
     * @details
     * 部分读取被视为错误。这比仅检查流的fail位更严格，可避免将截断的表
     * 解释为有效的ELF数据。
     */
    std::expected<void, std::string> read_exact_at(std::ifstream &file,
                                                   std::uint64_t offset,
                                                   void *buffer,
                                                   std::size_t bytes,
                                                   std::string_view what)
    {
        if (buffer == nullptr)
        {
            return make_error("内部错误：读取缓冲区为空(internal error: null read buffer)");
        }
        if (!can_cast_to_streamoff(offset))
        {
            return make_error(std::string(what) + "偏移过大(offset is too large)");
        }
        if (!can_cast_to_streamsize(bytes))
        {
            return make_error(std::string(what) + "大小过大(size is too large)");
        }
        file.clear();
        file.seekg(static_cast<std::streamoff>(offset), std::ios::beg);
        if (!file)
        {
            return make_error("无法定位到" + std::string(what) + "(failed to seek)");
        }
        file.read(static_cast<char *>(buffer), static_cast<std::streamsize>(bytes));
        if (file.gcount() != static_cast<std::streamsize>(bytes))
        {
            return make_error("无法完整读取" + std::string(what) + "(failed to read complete object)");
        }
        return {};
    }
    /**
     * @brief 从文件中读取一个可平凡复制的ELF结构。
     * @tparam T 由`NPC_ISA64`选择的原生ELF结构类型。
     * @param file std::ifstream&，已打开的ELF文件流。
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
     * @brief 将同质的ELF表读入vector。
     * @tparam T 原生ELF表项类型。
     * @param file std::ifstream&，已打开的ELF文件流。
     * @param offset std::uint64_t，该表的绝对文件偏移。
     * @param count std::size_t，表中预期的条目数。
     * @param what std::string_view，该表的诊断名称。
     * @return std::expected<std::vector<T>, std::string>，条目组成的vector，或错误字符串。
     */
    template <typename T>
    std::expected<std::vector<T>, std::string> read_array_at(std::ifstream &file,
                                                             std::uint64_t offset,
                                                             std::size_t count,
                                                             std::string_view what)
    {
        auto bytes{std::size_t{0}};
        if (!checked_byte_count(count, sizeof(T), bytes))
        {
            return make_error(std::string(what) + "大小溢出size_t(size overflows size_t)");
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
     * @brief 在解释其他表之前验证ELF头。
     * @param header const Header&，从文件偏移零处读取的头。
     * @return std::expected<void, std::string>，空的成功值，或此ELF不受支持的原因。
     * @details
     * 解析器有意仅支持NPC所需的常见情况：小端ELF文件，且其 32/64 位class
     * 与构建匹配。条目大小检查可防止对具有不兼容主机结构布局的表进行意外重解释。
     */
    std::expected<void, std::string> validate_header(const Header &header)
    {
        if (header.e_ident[EI_MAG0] != ELFMAG0 ||
            header.e_ident[EI_MAG1] != ELFMAG1 ||
            header.e_ident[EI_MAG2] != ELFMAG2 ||
            header.e_ident[EI_MAG3] != ELFMAG3)
        {
            return make_error("不是ELF文件(not an ELF file)");
        }
        if (header.e_ident[EI_CLASS] != ExpectedElfClass)
        {
            return make_error("ELF类别不匹配(ELF class mismatch)：期望(expected)" + std::string(ExpectedElfClassName));
        }
        if (header.e_ident[EI_DATA] != ELFDATA2LSB)
        {
            return make_error("仅支持小端序ELF文件(only little-endian ELF files are supported)");
        }
        if (header.e_ident[EI_VERSION] != EV_CURRENT || header.e_version != EV_CURRENT)
        {
            return make_error("不支持的ELF版本(unsupported ELF version)");
        }
        if (header.e_ehsize != sizeof(Header))
        {
            return make_error("ELF文件头大小不匹配(ELF header size mismatch)");
        }
        if (header.e_shoff != 0 && header.e_shentsize != sizeof(SectionHeader))
        {
            return make_error("节头表项大小不匹配(section header entry size mismatch)");
        }
        if (header.e_phoff != 0 && header.e_phnum != 0 && header.e_phentsize != sizeof(Readelf::program_header_type))
        {
            return make_error("程序头表项大小不匹配(program header entry size mismatch)");
        }
        return {};
    }
    /**
     * @brief 读取完整的节头表。
     * @param file std::ifstream&，已打开的ELF文件流。
     * @param header const Header&，已验证的ELF头。
     * @return std::expected<std::vector<SectionHeader>, std::string>，节头条目；若ELF没有常规表则返回错误。
     */
    std::expected<std::vector<SectionHeader>, std::string> read_section_headers(std::ifstream &file, const Header &header)
    {
        if (header.e_shoff == 0 || header.e_shnum == 0)
        {
            return make_error("ELF文件没有常规节头表(ELF file has no regular section header table)");
        }
        return read_array_at<SectionHeader>(file,
                                            static_cast<std::uint64_t>(header.e_shoff),
                                            static_cast<std::size_t>(header.e_shnum),
                                            "节头表(section header table)");
    }
    /**
     * @brief 读取常规的`SHT_SYMTAB`节。
     * @param file std::ifstream&，已打开的ELF文件流。
     * @param section const SectionHeader&，描述符号表的节头。
     * @return std::expected<std::vector<Symbol>, std::string>，原始符号条目；若该节格式错误则返回错误。
     */
    std::expected<std::vector<Symbol>, std::string> read_symbols(std::ifstream &file, const SectionHeader &section)
    {
        if (section.sh_type != SHT_SYMTAB)
        {
            return make_error("节不是SHT_SYMTAB(section is not SHT_SYMTAB)");
        }
        if (section.sh_offset == 0 || section.sh_size == 0 || section.sh_entsize == 0)
        {
            return make_error("符号表节为空或无效(symbol table section is empty or invalid)");
        }
        if (section.sh_entsize != sizeof(Symbol) || section.sh_size % section.sh_entsize != 0)
        {
            return make_error("符号表项大小不匹配(symbol table entry size mismatch)");
        }
        return read_array_at<Symbol>(file,
                                     static_cast<std::uint64_t>(section.sh_offset),
                                     static_cast<std::size_t>(section.sh_size / section.sh_entsize),
                                     "符号表(symbol table)");
    }
    /**
     * @brief 读取字符串表并追加一个防御性的尾部NUL字节。
     * @param file std::ifstream&，已打开的ELF文件流。
     * @param section const SectionHeader&，描述`SHT_STRTAB`节的节头。
     * @return std::expected<std::vector<char>, std::string>，字符串表字节加一个额外的NUL，或错误字符串。
     * @details
     * ELF字符串表应包含位于节负载内的NUL终止字符串。额外的终止符不计入
     * 原始文件数据；它仅在验证后使意外显示最后一个字符串更安全。
     */
    std::expected<std::vector<char>, std::string> read_string_table(std::ifstream &file, const SectionHeader &section)
    {
        if (section.sh_type != SHT_STRTAB)
        {
            return make_error("节不是SHT_STRTAB(section is not SHT_STRTAB)");
        }
        if (section.sh_size == 0)
        {
            return make_error("字符串表为空(string table is empty)");
        }
        if (section.sh_size > static_cast<decltype(section.sh_size)>(std::numeric_limits<std::size_t>::max() - 1))
        {
            return make_error("字符串表过大(string table is too large)");
        }
        const auto payload_size{static_cast<std::size_t>(section.sh_size)};
        std::vector<char> table(payload_size + 1, '\0');
        auto result{read_exact_at(file,
                                  static_cast<std::uint64_t>(section.sh_offset),
                                  table.data(),
                                  payload_size,
                                  "字符串表(string table)")};
        if (!result)
        {
            return make_error(result.error());
        }
        return table;
    }
    /**
     * @brief 仅返回来自ELF字符串表节的字节。
     * @param table const std::vector<char>&，缓存的表，包含加载器额外添加的尾部NUL字节。
     * @return std::span<const char>，排除合成尾部NUL的span。
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
     * @brief 将字符串表偏移解析为有界的`std::string_view`。
     * @param table std::span<const char>，原始字符串表字节。
     * @param offset std::size_t，`table`内的字节偏移。
     * @return std::optional<std::string_view>，NUL终止字符串的视图；若偏移无效或字符串未在原始字节内终止，
     *         则返回`std::nullopt`。
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
     * @brief 从ELF符号的`st_info`字段中提取绑定位。
     * @param info unsigned char，原始`st_info`字节。
     * @return unsigned，`STB_*`绑定值。
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
     * @brief 从ELF符号的`st_info`字段中提取类型位。
     * @param info unsigned char，原始`st_info`字节。
     * @return unsigned，`STT_*`符号类型值。
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
     * @brief 从ELF符号的`st_other`字段中提取可见性位。
     * @param other unsigned char，原始`st_other`字节。
     * @return unsigned，`STV_*`可见性值。
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
     * @param symbols std::span<const Symbol>，来自`SHT_SYMTAB`的原始条目。
     * @param string_table std::span<const char>，符号字符串表负载。
     * @return std::vector<ReadelfFunction>，按起始地址排序的函数记录。
     * @details
     * 过滤器保留已定义的、大小非零且名称有效的`STT_FUNC`符号。跳过大小为零
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
        std::ranges::sort(functions, {}, [](const ReadelfFunction &f) { return f.start; });
        return functions;
    }
    /**
     * @brief 将ELF class字节转换为显示字符串。
     * @param elf_class unsigned char，`EI_CLASS`值。
     * @return std::string_view，用于类似readelf输出的稳定字符串字面量。
     */
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
    /**
     * @brief 将ELF端序编码字节转换为显示字符串。
     * @param data unsigned char，`EI_DATA`值。
     * @return std::string_view，用于类似readelf输出的稳定字符串字面量。
     */
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
    /**
     * @brief 将ELF文件类型值转换为显示字符串。
     * @param type Half，原始`e_type`值。
     * @return std::string_view，用于类似readelf输出的稳定字符串字面量。
     */
    std::string_view elf_type_name(Half type)
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
    /**
     * @brief 将ELF节类型值转换为紧凑的显示字符串。
     * @param type Word，原始`sh_type`值。
     * @return std::string_view，用于类似readelf输出的稳定字符串字面量。
     */
    std::string_view section_type_name(Word type)
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
    /**
     * @brief 使用GNU readelf风格的短字母渲染节标志位。
     * @param flags Xword，原始`sh_flags`位集合。
     * @return std::string，如`WA`、`AX`或`MS`的紧凑字符串。
     */
    std::string section_flags(Xword flags)
    {
        std::string result;
        const auto append_if{[&](Xword mask, char flag)
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
    /**
     * @brief 将符号绑定转换为显示字符串。
     * @param info unsigned char，原始`st_info`字节。
     * @return std::string_view，用于类似readelf输出的稳定字符串字面量。
     */
    std::string_view symbol_bind_name(unsigned char info)
    {
        switch (symbol_bind(info))
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
    /**
     * @brief 将符号类型转换为显示字符串。
     * @param info unsigned char，原始`st_info`字节。
     * @return std::string_view，用于类似readelf输出的稳定字符串字面量。
     */
    std::string_view symbol_type_name(unsigned char info)
    {
        switch (symbol_type(info))
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
    /**
     * @brief 将符号可见性值转换为显示字符串。
     * @param other unsigned char，原始`st_other`字节。
     * @return std::string_view，用于类似readelf输出的稳定字符串字面量。
     */
    std::string_view symbol_visibility_name(unsigned char other)
    {
        switch (symbol_visibility(other))
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
    /**
     * @brief 格式化符号节索引以供显示。
     * @param section_index Half，原始`st_shndx`值。
     * @return std::string，`UND`、`ABS`、`COM`或十进制节索引。
     */
    std::string symbol_section_index(Half section_index)
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
    std::uint32_t read_utf8_code_point(std::string_view text, std::size_t &index)
    {
        const auto first{static_cast<unsigned char>(text[index++])};
        if (first < 0x80u)
        {
            return first;
        }
        auto need{std::size_t{0}};
        auto code_point{std::uint32_t{0}};
        if ((first & 0xe0u) == 0xc0u)
        {
            need = 1;
            code_point = first & 0x1fu;
        }
        else if ((first & 0xf0u) == 0xe0u)
        {
            need = 2;
            code_point = first & 0x0fu;
        }
        else if ((first & 0xf8u) == 0xf0u)
        {
            need = 3;
            code_point = first & 0x07u;
        }
        else
        {
            return 0xfffdu;
        }
        if (index + need > text.size())
        {
            index = text.size();
            return 0xfffdu;
        }
        for (std::size_t i{0}; i < need; ++i)
        {
            const auto next{static_cast<unsigned char>(text[index])};
            if ((next & 0xc0u) != 0x80u)
            {
                return 0xfffdu;
            }
            ++index;
            code_point = (code_point << 6u) | (next & 0x3fu);
        }
        return code_point;
    }
    bool is_wide_code_point(std::uint32_t code_point)
    {
        return (code_point >= 0x1100u && code_point <= 0x115fu) ||
               (code_point >= 0x2329u && code_point <= 0x232au) ||
               (code_point >= 0x2e80u && code_point <= 0xa4cfu) ||
               (code_point >= 0xac00u && code_point <= 0xd7a3u) ||
               (code_point >= 0xf900u && code_point <= 0xfaffu) ||
               (code_point >= 0xfe10u && code_point <= 0xfe19u) ||
               (code_point >= 0xfe30u && code_point <= 0xfe6fu) ||
               (code_point >= 0xff00u && code_point <= 0xff60u) ||
               (code_point >= 0xffe0u && code_point <= 0xffe6u) ||
               (code_point >= 0x20000u && code_point <= 0x3fffdu);
    }
    std::size_t code_point_width(std::uint32_t code_point)
    {
        if (code_point == 0 || code_point < 0x20u ||
            (code_point >= 0x7fu && code_point < 0xa0u) ||
            (code_point >= 0x0300u && code_point <= 0x036fu))
        {
            return 0;
        }
        return is_wide_code_point(code_point) ? 2u : 1u;
    }
    std::size_t display_width(std::string_view text)
    {
        auto width{std::size_t{0}};
        for (std::size_t index{0}; index < text.size();)
        {
            width += code_point_width(read_utf8_code_point(text, index));
        }
        return width;
    }
    std::string clip_to_width(std::string_view text, std::size_t width)
    {
        auto result{std::string{}};
        auto used{std::size_t{0}};
        for (std::size_t index{0}; index < text.size();)
        {
            const auto begin{index};
            const auto code_point{read_utf8_code_point(text, index)};
            const auto next_width{code_point_width(code_point)};
            if (used + next_width > width)
            {
                break;
            }
            result.append(text.substr(begin, index - begin));
            used += next_width;
        }
        return result;
    }
    std::string pad_right(std::string_view text, std::size_t width)
    {
        auto result{clip_to_width(text, width)};
        const auto used{display_width(result)};
        if (used < width)
        {
            result.append(width - used, ' ');
        }
        return result;
    }
    std::string pad_left(std::string_view text, std::size_t width)
    {
        auto clipped{clip_to_width(text, width)};
        const auto used{display_width(clipped)};
        if (used >= width)
        {
            return clipped;
        }
        return std::string(width - used, ' ') + clipped;
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
} // namespace
Readelf::Readelf() = default;
Readelf::Readelf(Readelf &&) noexcept = default;
Readelf &Readelf::operator=(Readelf &&) noexcept = default;
Readelf::~Readelf() = default;
/**
 * @brief 从路径创建已加载的`Readelf`实例。
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
 * @brief 报告此对象当前是否拥有已解析的ELF数据。
 * @return bool，成功加载后返回`true`。
 */
bool Readelf::loaded() const noexcept
{
    return loaded_;
}
/**
 * @brief 返回用于加载的源路径。
 * @return const std::filesystem::path&，存储路径的引用。
 */
const std::filesystem::path &Readelf::path() const noexcept
{
    return path_;
}
/**
 * @brief 返回缓存的ELF头。
 * @return const Readelf::header_type&，已解析头部的引用。
 */
const Readelf::header_type &Readelf::header() const
{
    return header_;
}
/**
 * @brief 返回缓存的节头表。
 * @return std::span<const Readelf::section_header_type>，覆盖所有节头的span。
 */
std::span<const Readelf::section_header_type> Readelf::section_headers() const noexcept
{
    return section_headers_;
}
/**
 * @brief 返回缓存的原始符号表。
 * @return std::span<const Readelf::symbol_type>，覆盖`SHT_SYMTAB`条目的span；若不存在常规符号表则为空span。
 */
std::span<const Readelf::symbol_type> Readelf::symbols() const noexcept
{
    return symbols_;
}
/**
 * @brief 返回派生的函数记录。
 * @return std::span<const ReadelfFunction>，按起始地址排序的函数符号span。
 */
std::span<const ReadelfFunction> Readelf::functions() const noexcept
{
    return functions_;
}
/**
 * @brief 查找其半开范围包含`address`的函数。
 * @param address std::size_t，要解析的虚拟地址。
 * @return std::optional<ReadelfFunction>，命中时返回函数记录，否则返回`std::nullopt`。
 * @details
 * 函数记录按起始地址排序，因此查找时先定位起始不大于`address`的最后一个
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
 * @param address std::size_t，要解析的虚拟地址。
 * @return std::optional<std::string_view>，命中时返回名称视图，否则返回`std::nullopt`。
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
 * @brief 打印类似readelf的ELF头摘要。
 * @param os std::ostream&，目标流。
 */
void Readelf::print_file_header(std::ostream &os) const
{
    if (!loaded_)
    {
        print_to(os, "Readelf尚未加载(Readelf is not loaded)\n");
        return;
    }
    print_to(os, "ELF Header:(ELF文件头)\n");
    auto magic{std::string{}};
    for (unsigned char byte : header_.e_ident)
    {
        if (!magic.empty())
        {
            magic.push_back(' ');
        }
        magic += std::format("{:02x}", static_cast<unsigned>(byte));
    }
    print_header_field(os, "Magic:(魔数)", magic);
    print_header_field(os, "Class:(类别)", elf_class_name(header_.e_ident[EI_CLASS]));
    print_header_field(os, "Data:(数据编码)", elf_data_name(header_.e_ident[EI_DATA]));
    print_header_field(os, "Version:(版本)", std::to_string(static_cast<unsigned>(header_.e_ident[EI_VERSION])));
    print_header_field(os, "OS/ABI:(操作系统/ABI)", std::to_string(static_cast<unsigned>(header_.e_ident[EI_OSABI])));
    print_header_field(os, "ABI Version:(ABI版本)", std::to_string(static_cast<unsigned>(header_.e_ident[EI_ABIVERSION])));
    print_header_field(os, "Type:(类型)", elf_type_name(header_.e_type));
    print_header_field(os, "Machine:(机器)", std::to_string(header_.e_machine));
    print_header_field(os, "Version:(版本)", format_hex(header_.e_version, 0));
    print_header_field(os, "Entry point address:(入口点地址)", format_hex(static_cast<std::uint64_t>(header_.e_entry), AddressWidth));
    print_header_field(os,
                       "Start of program headers:(程序头起始位置)",
                       std::format("{0} (bytes into file)(文件内字节偏移)", static_cast<unsigned long long>(header_.e_phoff)));
    print_header_field(os,
                       "Start of section headers:(节头起始位置)",
                       std::format("{0} (bytes into file)(文件内字节偏移)", static_cast<unsigned long long>(header_.e_shoff)));
    print_header_field(os, "Flags:(标志)", format_hex(header_.e_flags, 0));
    print_header_field(os, "Size of this header:(本文件头大小)", std::format("{0} (bytes)(字节)", header_.e_ehsize));
    print_header_field(os, "Size of program headers:(程序头表项大小)", std::format("{0} (bytes)(字节)", header_.e_phentsize));
    print_header_field(os, "Number of program headers:(程序头数量)", std::to_string(header_.e_phnum));
    print_header_field(os, "Size of section headers:(节头表项大小)", std::format("{0} (bytes)(字节)", header_.e_shentsize));
    print_header_field(os, "Number of section headers:(节头数量)", std::to_string(header_.e_shnum));
    print_header_field(os, "Section header string table index:(节头字符串表索引)", std::to_string(header_.e_shstrndx));
}
/**
 * @brief 打印类似readelf的节头表。
 * @param os std::ostream&，目标流。
 */
void Readelf::print_section_headers(std::ostream &os) const
{
    if (!loaded_)
    {
        print_to(os, "Readelf尚未加载(Readelf is not loaded)\n");
        return;
    }
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
    for (std::size_t i{0}; i < section_headers_.size(); ++i)
    {
        const auto &section{section_headers_[i]};
        print_to(os,
                 "  {} {} {} {} {} {} {} {} {} {} {}\n",
                 pad_right(std::format("[{:2}]", i), IndexWidth),
                 pad_right(section_name(section.sh_name), NameWidth),
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
/**
 * @brief 打印类似readelf的原始符号表。
 * @param os std::ostream&，目标流。
 */
void Readelf::print_symbols(std::ostream &os) const
{
    if (!loaded_)
    {
        print_to(os, "Readelf尚未加载(Readelf is not loaded)\n");
        return;
    }
    const auto symbol_table_index{symbol_table_section_index()};
    const auto table_name{symbol_table_index >= 0 ? section_name(section_headers_[symbol_table_index].sh_name) : std::string_view("<symtab>(<符号表>)")};
    print_to(os, "Symbol table '{}' contains {} entries:(符号表'{}'包含{}个条目)\n",
             table_name,
             symbols_.size(),
             table_name,
             symbols_.size());
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
    for (std::size_t i{0}; i < symbols_.size(); ++i)
    {
        const auto &symbol{symbols_[i]};
        print_to(os,
                 "  {} {} {} {} {} {} {} {}\n",
                 pad_left(std::format("{:4}:", i), NumWidth),
                 pad_left(std::format("{0:0{1}x}", static_cast<unsigned long long>(symbol.st_value), AddressWidth), ValueWidth),
                 pad_left(std::to_string(static_cast<unsigned long long>(symbol.st_size)), SizeColumnWidth),
                 pad_right(symbol_type_name(symbol.st_info), TypeColumnWidth),
                 pad_right(symbol_bind_name(symbol.st_info), BindColumnWidth),
                 pad_right(symbol_visibility_name(symbol.st_other), VisibilityWidth),
                 pad_left(symbol_section_index(symbol.st_shndx), SectionIndexWidth),
                 symbol_name(symbol.st_name));
    }
}
/**
 * @brief 从`path_`填充此对象。
 * @return std::expected<void, std::string>，空的成功值，或描述性的读取/解析错误。
 * @details
 * 此方法首先重置所有缓存状态。在验证ELF头和节头之后，它会择机读取
 * `.shstrtab`以获得更好的节名打印效果。缺少常规符号表不是致命错误：
 * 该对象仍可打印头部，但`symbols()`和`functions()`将为空。
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
        return make_error("无法打开ELF文件(failed to open ELF file)：" + path_.string());
    }
    auto header{read_object_at<Header>(file, 0, "ELF文件头(ELF header)")};
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
    const auto symbol_table{std::ranges::find_if(*sections, [](const SectionHeader &section)
                                                 { return section.sh_type == SHT_SYMTAB; })};
    if (symbol_table != sections->end())
    {
        const auto string_table_index{static_cast<std::size_t>(symbol_table->sh_link)};
        if (string_table_index >= sections->size() || (*sections)[string_table_index].sh_type != SHT_STRTAB)
        {
            return make_error("符号表链接的字符串表无效(symbol table has an invalid linked string table)");
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
 * @brief 从`.shstrtab`解析节名。
 * @param name_offset word_type，`sh_name`中存储的字节偏移。
 * @return std::string_view，节名视图；数据缺失或错误时返回稳定的占位符。
 */
std::string_view Readelf::section_name(word_type name_offset) const noexcept
{
    const auto name{string_at(original_string_bytes(section_name_table_), static_cast<std::size_t>(name_offset))};
    if (!name)
    {
        return section_name_table_.empty() ? std::string_view("<no-shstrtab>(<无节名>)") : std::string_view("<bad-name>(<坏名称>)");
    }
    return *name;
}
/**
 * @brief 从符号字符串表解析符号名称。
 * @param name_offset word_type，`st_name`中存储的字节偏移。
 * @return std::string_view，符号名称视图；数据缺失或错误时返回稳定的占位符。
 */
std::string_view Readelf::symbol_name(word_type name_offset) const noexcept
{
    const auto name{string_at(original_string_bytes(string_table_), static_cast<std::size_t>(name_offset))};
    if (!name)
    {
        return string_table_.empty() ? std::string_view("<no-strtab>(<无串表>)") : std::string_view("<bad-name>(<坏名称>)");
    }
    return *name;
}
/**
 * @brief 定位第一个常规符号表节。
 * @return int，从零开始的节索引；若不存在则返回`-1`。
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
