module;
#include <elf.h>
module npc.elf.ELFFile;
import std;
namespace
{
constexpr unsigned char ExpectedELFClass{npc::elf::IsELF64 ? ELFCLASS64 : ELFCLASS32};
constexpr auto ExpectedELFClassName{std::string_view{npc::elf::IsELF64 ? "ELF64(64位)" : "ELF32(32位)"}};
std::unexpected<std::string> make_error(std::string message)
{
    return std::unexpected(std::move(message));
}
bool can_cast_to_streamoff(std::uint64_t value)
{
    return value <= static_cast<std::uint64_t>(std::numeric_limits<std::streamoff>::max());
}
bool can_cast_to_streamsize(std::size_t value)
{
    return value <= static_cast<std::size_t>(std::numeric_limits<std::streamsize>::max());
}
}
namespace npc::elf
{
std::expected<ELFFile, std::string> ELFFile::open(std::filesystem::path path)
{
    ELFFile file;
    file.path_ = std::move(path);
    file.file_.open(file.path_, std::ios::binary);
    if (!file.file_)
    {
        return make_error("无法打开ELF文件(failed to open ELF file)：" + file.path_.string());
    }
    file.file_.clear();
    file.file_.seekg(0, std::ios::end);
    const auto end{file.file_.tellg()};
    file.file_.clear();
    if (end >= 0)
    {
        file.size_ = static_cast<std::uint64_t>(end);
    }
    return file;
}
std::optional<std::uint64_t> ELFFile::size() const noexcept
{
    return size_;
}
std::expected<void, std::string> ELFFile::read_at(std::uint64_t offset,
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
    file_.clear();
    file_.seekg(static_cast<std::streamoff>(offset), std::ios::beg);
    if (!file_)
    {
        return make_error("无法定位到" + std::string(what) + "(failed to seek)");
    }
    file_.read(static_cast<char *>(buffer), static_cast<std::streamsize>(bytes));
    if (file_.gcount() != static_cast<std::streamsize>(bytes))
    {
        return make_error("无法完整读取" + std::string(what) + "(failed to read complete object)");
    }
    return {};
}
std::expected<void, std::string> validate_header(const Header &header)
{
    if (header.e_ident[EI_MAG0] != ELFMAG0 ||
        header.e_ident[EI_MAG1] != ELFMAG1 ||
        header.e_ident[EI_MAG2] != ELFMAG2 ||
        header.e_ident[EI_MAG3] != ELFMAG3)
    {
        return make_error("不是ELF文件(not an ELF file)");
    }
    if (header.e_ident[EI_CLASS] != ExpectedELFClass)
    {
        return make_error("ELF类别不匹配(ELF class mismatch)：期望(expected)" + std::string(ExpectedELFClassName));
    }
    if (header.e_ident[EI_DATA] != ELFDATA2LSB)
    {
        return make_error("仅支持小端序ELF文件(only little-endian ELF files are supported)");
    }
    if (header.e_ident[EI_VERSION] != EV_CURRENT || header.e_version != EV_CURRENT)
    {
        return make_error("不支持的ELF版本(unsupported ELF version)");
    }
    if (header.e_machine != EM_RISCV)
    {
        return make_error(std::format("ELF目标架构不匹配(ELF machine mismatch)：仅支持RISC-V (expected EM_RISCV={}, actual e_machine={})", EM_RISCV, static_cast<unsigned>(header.e_machine)));
    }
    if (header.e_ehsize != sizeof(Header))
    {
        return make_error("ELF文件头大小不匹配(ELF header size mismatch)");
    }
    if (header.e_shoff != 0 && header.e_shentsize != sizeof(SectionHeader))
    {
        return make_error("节头表项大小不匹配(section header entry size mismatch)");
    }
    if (header.e_phoff != 0 && header.e_phnum != 0 && header.e_phentsize != sizeof(ProgramHeader))
    {
        return make_error("程序头表项大小不匹配(program header entry size mismatch)");
    }
    return {};
}
std::expected<std::vector<SectionHeader>, std::string> read_section_headers(ELFFile &file, const Header &header)
{
    if (header.e_shoff == 0 || header.e_shnum == 0)
    {
        return make_error("ELF文件没有常规节头表(ELF file has no regular section header table)");
    }
    return file.read_array_at<SectionHeader>(static_cast<std::uint64_t>(header.e_shoff),
                                             static_cast<std::size_t>(header.e_shnum),
                                             "节头表(section header table)");
}
std::expected<std::vector<Symbol>, std::string> read_symbols(ELFFile &file, const SectionHeader &section)
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
    return file.read_array_at<Symbol>(static_cast<std::uint64_t>(section.sh_offset),
                                      static_cast<std::size_t>(section.sh_size / section.sh_entsize),
                                      "符号表(symbol table)");
}
std::expected<std::vector<char>, std::string> read_string_table(ELFFile &file, const SectionHeader &section)
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
    if (!detail::CheckRange(file.size(), static_cast<std::uint64_t>(section.sh_offset), payload_size))
    {
        return make_error("字符串表超出文件范围(string table exceeds file size)");
    }
    std::vector<char> table(payload_size + 1, '\0');
    auto result{file.read_at(static_cast<std::uint64_t>(section.sh_offset),
                             table.data(),
                             payload_size,
                             "字符串表(string table)")};
    if (!result)
    {
        return make_error(std::move(result.error()));
    }
    return table;
}
}
