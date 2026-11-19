module;
#include <elf.h>
export module npc.elf.ELFFile;
import npc.config;
import std;
export namespace npc::elf
{
inline constexpr bool IsELF64{npc::XLen == 64};
using Header = std::conditional_t<IsELF64, Elf64_Ehdr, Elf32_Ehdr>;
using SectionHeader = std::conditional_t<IsELF64, Elf64_Shdr, Elf32_Shdr>;
using Symbol = std::conditional_t<IsELF64, Elf64_Sym, Elf32_Sym>;
using ProgramHeader = std::conditional_t<IsELF64, Elf64_Phdr, Elf32_Phdr>;
using Half = std::conditional_t<IsELF64, Elf64_Half, Elf32_Half>;
using Word = std::conditional_t<IsELF64, Elf64_Word, Elf32_Word>;
using Xword = std::conditional_t<IsELF64, Elf64_Xword, Elf32_Word>;
class ELFFile final
{
public:
    ELFFile() = default;
    ELFFile(const ELFFile &) = delete;
    ELFFile &operator=(const ELFFile &) = delete;
    ELFFile(ELFFile &&) = default;
    ELFFile &operator=(ELFFile &&) = default;
    ~ELFFile() = default;
    [[nodiscard]] static std::expected<ELFFile, std::string> open(std::filesystem::path path);
    // 无法确定文件大小时为 nullopt。
    [[nodiscard]] std::optional<std::uint64_t> size() const noexcept;
    // 短读视为错误。
    std::expected<void, std::string> read_at(std::uint64_t offset,
                                             void *buffer,
                                             std::size_t bytes,
                                             std::string_view what);
    template <typename T>
    [[nodiscard]] std::expected<T, std::string> read_object_at(std::uint64_t offset, std::string_view what)
    {
        T object{};
        auto result{read_at(offset, &object, sizeof(T), what)};
        if (!result)
        {
            return std::unexpected(std::move(result.error()));
        }
        return object;
    }
    template <typename T>
    [[nodiscard]] std::expected<std::vector<T>, std::string> read_array_at(std::uint64_t offset,
                                                                           std::size_t count,
                                                                           std::string_view what)
    {
        constexpr auto ItemSize{sizeof(T)};
        if (count > std::numeric_limits<std::size_t>::max() / ItemSize)
        {
            return std::unexpected(std::string(what) + "大小溢出size_t(size overflows size_t)");
        }
        const auto bytes{count * ItemSize};
        const auto file_size{size()};
        if (!file_size || offset > *file_size || bytes > *file_size - offset)
        {
            return std::unexpected(std::string(what) + "超出文件范围(range exceeds file size)");
        }
        std::vector<T> objects(count);
        if (bytes == 0)
        {
            return objects;
        }
        auto result{read_at(offset, objects.data(), bytes, what)};
        if (!result)
        {
            return std::unexpected(std::move(result.error()));
        }
        return objects;
    }
private:
    std::filesystem::path path_{};
    std::ifstream file_{};
    std::optional<std::uint64_t> size_{};
};
// 校验 RISC-V 小端 ELF 头。
[[nodiscard]] std::expected<void, std::string> validate_header(const Header &header);
[[nodiscard]] std::expected<std::vector<SectionHeader>, std::string> read_section_headers(ELFFile &file,
                                                                                         const Header &header);
[[nodiscard]] std::expected<std::vector<Symbol>, std::string> read_symbols(ELFFile &file,
                                                                           const SectionHeader &section);
// 字符串表额外追加尾部 NUL。
[[nodiscard]] std::expected<std::vector<char>, std::string> read_string_table(ELFFile &file,
                                                                              const SectionHeader &section);
}
