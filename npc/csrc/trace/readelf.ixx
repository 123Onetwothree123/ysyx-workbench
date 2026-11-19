export module npc.trace.readelf;
import std;
import npc.trace.ReadelfFunction;
import npc.elf.ELFFile;

export class Readelf final
{
public:
    static constexpr bool is_elf64{npc::elf::IsELF64};
    using header_type = npc::elf::Header;
    using section_header_type = npc::elf::SectionHeader;
    using symbol_type = npc::elf::Symbol;
    using program_header_type = npc::elf::ProgramHeader;
    using half_type = npc::elf::Half;
    using word_type = npc::elf::Word;
    using xword_type = npc::elf::Xword;

    Readelf();
    // 字符串视图指向内部存储，禁止复制。
    Readelf(const Readelf &) = delete;
    Readelf &operator=(const Readelf &) = delete;
    Readelf(Readelf &&) noexcept;
    Readelf &operator=(Readelf &&) noexcept;
    ~Readelf();

    [[nodiscard]] static std::expected<Readelf, std::string> load(std::filesystem::path elf_file);
    [[nodiscard]] bool loaded() const noexcept;
    [[nodiscard]] const std::filesystem::path &path() const noexcept;
    [[nodiscard]] const header_type &header() const;
    [[nodiscard]] std::span<const section_header_type> section_headers() const noexcept;
    [[nodiscard]] std::span<const symbol_type> symbols() const noexcept;
    [[nodiscard]] std::span<const ReadelfFunction> functions() const noexcept;

    [[nodiscard]] std::optional<ReadelfFunction> find_function(std::size_t address) const noexcept;
    [[nodiscard]] std::optional<std::string_view> find_function_name(std::size_t address) const noexcept;

    void print_file_header(std::ostream &os) const;
    void print_section_headers(std::ostream &os) const;
    void print_symbols(std::ostream &os) const;

private:
    std::expected<void, std::string> load_from_file();

    bool loaded_{false};
    std::filesystem::path path_{};
    header_type header_{};
    std::vector<section_header_type> section_headers_{};
    std::vector<symbol_type> symbols_{};
    // 两张字符串表均额外追加一个尾部 NUL。
    std::vector<char> string_table_{};
    std::vector<char> section_name_table_{};
    std::vector<ReadelfFunction> functions_{};
};
