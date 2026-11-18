// Readelf 单元回归：ELF32 / ELF64 双模式。
//
// 测试编译时通过 xlen.mk 消费 Kconfig 的 CONFIG_XLEN；ELF class 由核心
// readelf 根据同一个 CONFIG_XLEN 派生，测试内部不再固定断言“只能 ELF32”。
#include <elf.h>

import std;
import npc.trace.readelf;

namespace
{
int failures{};

void check(bool condition, std::string_view message)
{
    if (condition)
        return;
    ++failures;
    std::println(std::cerr, "[FAIL] {}", message);
}

std::filesystem::path fixture_path(std::string_view name)
{
    const auto nonce{std::chrono::steady_clock::now().time_since_epoch().count()};
    return std::filesystem::temp_directory_path() /
           std::format("npc-readelf-{}-{}.elf", name, nonce);
}

template <typename T>
void put_bytes(std::vector<char> &image, std::size_t offset, const T &value)
{
    std::memcpy(image.data() + offset, &value, sizeof(T));
}

constexpr std::size_t align_up(std::size_t value, std::size_t alignment)
{
    return (value + alignment - 1) / alignment * alignment;
}

// 按目标 ELF class 生成最小的 RISC-V 可执行文件。
// 64 位模式的入口地址故意放在 4GiB 以上，用来验证高地址符号查询。
template <bool IsElf64>
bool write_riscv_fixture(const std::filesystem::path &path,
                         Elf64_Half machine,
                         std::uint64_t entry)
{
    using Header = std::conditional_t<IsElf64, Elf64_Ehdr, Elf32_Ehdr>;
    using SectionHeader = std::conditional_t<IsElf64, Elf64_Shdr, Elf32_Shdr>;
    using Symbol = std::conditional_t<IsElf64, Elf64_Sym, Elf32_Sym>;

    constexpr std::string_view symbol_strings{"\0entry\0", 7};
    constexpr std::string_view section_strings{
        "\0.text\0.strtab\0.symtab\0.shstrtab\0", 33};

    constexpr std::size_t text_offset{align_up(sizeof(Header), 8)};
    constexpr std::size_t text_size{4};
    constexpr std::size_t string_table_offset{text_offset + text_size};
    const std::size_t symbol_table_offset{
        align_up(string_table_offset + symbol_strings.size(), alignof(Symbol))};
    const std::size_t section_name_offset{
        symbol_table_offset + 2 * sizeof(Symbol)};
    const std::size_t section_headers_offset{
        align_up(section_name_offset + section_strings.size(),
                 alignof(SectionHeader))};

    std::vector<char> image(
        section_headers_offset + 5 * sizeof(SectionHeader), '\0');

    Header header{};
    header.e_ident[EI_MAG0] = ELFMAG0;
    header.e_ident[EI_MAG1] = ELFMAG1;
    header.e_ident[EI_MAG2] = ELFMAG2;
    header.e_ident[EI_MAG3] = ELFMAG3;
    header.e_ident[EI_CLASS] = IsElf64 ? ELFCLASS64 : ELFCLASS32;
    header.e_ident[EI_DATA] = ELFDATA2LSB;
    header.e_ident[EI_VERSION] = EV_CURRENT;
    header.e_type = ET_EXEC;
    header.e_machine = machine;
    header.e_version = EV_CURRENT;
    header.e_entry = static_cast<decltype(header.e_entry)>(entry);
    header.e_ehsize = sizeof(Header);
    header.e_shoff =
        static_cast<decltype(header.e_shoff)>(section_headers_offset);
    header.e_shentsize = sizeof(SectionHeader);
    header.e_shnum = 5;
    header.e_shstrndx = 4;
    put_bytes(image, 0, header);

    const std::uint32_t text{0x00000013U}; // addi x0,x0,0
    put_bytes(image, text_offset, text);

    std::memcpy(image.data() + string_table_offset, symbol_strings.data(),
                symbol_strings.size());

    std::array<Symbol, 2> symbols{};
    symbols[1].st_name = 1;
    symbols[1].st_value = static_cast<decltype(symbols[1].st_value)>(entry);
    symbols[1].st_size = sizeof(text);
    symbols[1].st_info = IsElf64
                             ? ELF64_ST_INFO(STB_GLOBAL, STT_FUNC)
                             : ELF32_ST_INFO(STB_GLOBAL, STT_FUNC);
    symbols[1].st_shndx = 1;
    std::memcpy(image.data() + symbol_table_offset, symbols.data(),
                sizeof(symbols));

    std::memcpy(image.data() + section_name_offset, section_strings.data(),
                section_strings.size());

    std::array<SectionHeader, 5> sections{};
    sections[1].sh_name = 1; // .text
    sections[1].sh_type = SHT_PROGBITS;
    sections[1].sh_flags = SHF_ALLOC | SHF_EXECINSTR;
    sections[1].sh_addr = static_cast<decltype(sections[1].sh_addr)>(entry);
    sections[1].sh_offset =
        static_cast<decltype(sections[1].sh_offset)>(text_offset);
    sections[1].sh_size = sizeof(text);
    sections[1].sh_addralign = 4;
    sections[2].sh_name = 7; // .strtab
    sections[2].sh_type = SHT_STRTAB;
    sections[2].sh_offset =
        static_cast<decltype(sections[2].sh_offset)>(string_table_offset);
    sections[2].sh_size = symbol_strings.size();
    sections[2].sh_addralign = 1;
    sections[3].sh_name = 15; // .symtab
    sections[3].sh_type = SHT_SYMTAB;
    sections[3].sh_offset =
        static_cast<decltype(sections[3].sh_offset)>(symbol_table_offset);
    sections[3].sh_size = sizeof(symbols);
    sections[3].sh_link = 2;
    sections[3].sh_info = 1;
    sections[3].sh_addralign = alignof(Symbol);
    sections[3].sh_entsize = sizeof(Symbol);
    sections[4].sh_name = 23; // .shstrtab
    sections[4].sh_type = SHT_STRTAB;
    sections[4].sh_offset =
        static_cast<decltype(sections[4].sh_offset)>(section_name_offset);
    sections[4].sh_size = section_strings.size();
    sections[4].sh_addralign = 1;
    std::memcpy(image.data() + section_headers_offset, sections.data(),
                sizeof(sections));

    std::ofstream output{path, std::ios::binary | std::ios::trunc};
    output.write(image.data(), static_cast<std::streamsize>(image.size()));
    return static_cast<bool>(output);
}

class FixtureFiles final
{
public:
    FixtureFiles()
        : riscv{fixture_path("riscv")},
          non_riscv{fixture_path("i386")},
          wrong_class{fixture_path("wrong-class")}
    {
    }

    ~FixtureFiles()
    {
        std::error_code error;
        std::filesystem::remove(riscv, error);
        error.clear();
        std::filesystem::remove(non_riscv, error);
        error.clear();
        std::filesystem::remove(wrong_class, error);
    }

    std::filesystem::path riscv;
    std::filesystem::path non_riscv;
    std::filesystem::path wrong_class;
};
} // namespace

int main()
{
    constexpr bool kElf64{Readelf::is_elf64};
    constexpr std::uint64_t kNativeEntry{kElf64 ? 0x1'0000'0000ULL
                                                : 0x80000000ULL};
    // 反 class 的样本地址只需要能放进对应位宽。
    constexpr std::uint64_t kWrongClassEntry{kElf64 ? 0x80000000ULL
                                                     : 0x1'0000'0000ULL};

    FixtureFiles fixtures;
    check(write_riscv_fixture<kElf64>(fixtures.riscv, EM_RISCV, kNativeEntry),
          std::format("failed to create the RISC-V {} fixture",
                      kElf64 ? "ELF64" : "ELF32"));
    check(write_riscv_fixture<kElf64>(fixtures.non_riscv, EM_386,
                                      kNativeEntry),
          std::format("failed to create the non-RISC-V {} fixture",
                      kElf64 ? "ELF64" : "ELF32"));
    check(write_riscv_fixture<!kElf64>(fixtures.wrong_class, EM_RISCV,
                                       kWrongClassEntry),
          std::format("failed to create the wrong-class {} fixture",
                      kElf64 ? "ELF32" : "ELF64"));

    auto riscv{Readelf::load(fixtures.riscv)};
    check(riscv.has_value(),
          std::format("a valid RISC-V {} file must load",
                      kElf64 ? "ELF64" : "ELF32"));
    if (riscv)
    {
        check(riscv->loaded(), "the accepted RISC-V ELF must be marked loaded");
        check(riscv->header().e_machine == EM_RISCV,
              "the accepted ELF must preserve its RISC-V machine type");
        check(static_cast<std::uint64_t>(riscv->header().e_entry) ==
                  kNativeEntry,
              "the accepted ELF must preserve its (possibly high) entry point");
        check(riscv->section_headers().size() == 5,
              "the accepted ELF must expose its complete section header table");
        check(riscv->functions().size() == 1 &&
                  riscv->functions().front().name == "entry",
              "the accepted RISC-V ELF must expose its function symbol");
        check(riscv->find_function_name(static_cast<std::size_t>(kNativeEntry)) ==
                  std::optional<std::string_view>{"entry"},
              "the accepted ELF function must be address-queryable");
    }

    const auto non_riscv{Readelf::load(fixtures.non_riscv)};
    check(!non_riscv,
          std::format("an {} file for a non-RISC-V machine must be rejected",
                      kElf64 ? "ELF64" : "ELF32"));
    if (!non_riscv)
    {
        check(non_riscv.error().contains("RISC-V"),
              "the machine mismatch error must name the required RISC-V architecture");
        check(non_riscv.error().contains("e_machine=3"),
              "the machine mismatch error must report the actual e_machine value");
    }

    const auto wrong_class{Readelf::load(fixtures.wrong_class)};
    check(!wrong_class,
          std::format("an {} file must be rejected by an {} build",
                      kElf64 ? "ELF32" : "ELF64",
                      kElf64 ? "ELF64" : "ELF32"));
    if (!wrong_class)
        check(wrong_class.error().contains("ELF类别"),
              "the class mismatch error must explain the expected ELF class");

    if (failures != 0)
        return 1;
    std::println("[PASS] Readelf accepts RISC-V {}, rejects non-RISC-V and "
                 "opposite-class ELF",
                 kElf64 ? "ELF64 high-address symbols" : "ELF32");
    return 0;
}
