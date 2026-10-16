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

bool write_elf32_fixture(const std::filesystem::path &path, Elf32_Half machine)
{
    constexpr std::string_view symbol_strings{"\0entry\0", 7};
    constexpr std::string_view section_strings{
        "\0.text\0.strtab\0.symtab\0.shstrtab\0", 33};
    constexpr Elf32_Off text_offset{sizeof(Elf32_Ehdr)};
    constexpr Elf32_Off string_table_offset{text_offset + sizeof(Elf32_Word)};
    constexpr Elf32_Off symbol_table_offset{64};
    constexpr Elf32_Off section_name_offset{96};
    constexpr Elf32_Off section_headers_offset{132};

    Elf32_Ehdr header{};
    header.e_ident[EI_MAG0] = ELFMAG0;
    header.e_ident[EI_MAG1] = ELFMAG1;
    header.e_ident[EI_MAG2] = ELFMAG2;
    header.e_ident[EI_MAG3] = ELFMAG3;
    header.e_ident[EI_CLASS] = ELFCLASS32;
    header.e_ident[EI_DATA] = ELFDATA2LSB;
    header.e_ident[EI_VERSION] = EV_CURRENT;
    header.e_type = ET_EXEC;
    header.e_machine = machine;
    header.e_version = EV_CURRENT;
    header.e_entry = 0x80000000U;
    header.e_ehsize = sizeof(Elf32_Ehdr);
    header.e_shoff = section_headers_offset;
    header.e_shentsize = sizeof(Elf32_Shdr);
    header.e_shnum = 5;
    header.e_shstrndx = 4;

    const Elf32_Word text{0x00000013U}; // addi x0,x0,0
    std::array<Elf32_Sym, 2> symbols{};
    symbols[1].st_name = 1;
    symbols[1].st_value = header.e_entry;
    symbols[1].st_size = sizeof(text);
    symbols[1].st_info = ELF32_ST_INFO(STB_GLOBAL, STT_FUNC);
    symbols[1].st_shndx = 1;

    std::array<Elf32_Shdr, 5> sections{};
    sections[1].sh_name = 1; // .text
    sections[1].sh_type = SHT_PROGBITS;
    sections[1].sh_flags = SHF_ALLOC | SHF_EXECINSTR;
    sections[1].sh_addr = header.e_entry;
    sections[1].sh_offset = text_offset;
    sections[1].sh_size = sizeof(text);
    sections[1].sh_addralign = 4;
    sections[2].sh_name = 7; // .strtab
    sections[2].sh_type = SHT_STRTAB;
    sections[2].sh_offset = string_table_offset;
    sections[2].sh_size = symbol_strings.size();
    sections[2].sh_addralign = 1;
    sections[3].sh_name = 15; // .symtab
    sections[3].sh_type = SHT_SYMTAB;
    sections[3].sh_offset = symbol_table_offset;
    sections[3].sh_size = sizeof(symbols);
    sections[3].sh_link = 2;
    sections[3].sh_info = 1;
    sections[3].sh_addralign = 4;
    sections[3].sh_entsize = sizeof(Elf32_Sym);
    sections[4].sh_name = 23; // .shstrtab
    sections[4].sh_type = SHT_STRTAB;
    sections[4].sh_offset = section_name_offset;
    sections[4].sh_size = section_strings.size();
    sections[4].sh_addralign = 1;

    std::ofstream output{path, std::ios::binary};
    output.write(reinterpret_cast<const char *>(&header), sizeof(header));
    output.write(reinterpret_cast<const char *>(&text), sizeof(text));
    output.write(symbol_strings.data(), static_cast<std::streamsize>(symbol_strings.size()));
    output.put('\0'); // align .symtab to four bytes
    output.write(reinterpret_cast<const char *>(symbols.data()), sizeof(symbols));
    output.write(section_strings.data(), static_cast<std::streamsize>(section_strings.size()));
    output.write("\0\0\0", 3); // align the section header table
    output.write(reinterpret_cast<const char *>(sections.data()), sizeof(sections));
    return static_cast<bool>(output);
}

class FixtureFiles final
{
public:
    FixtureFiles()
        : riscv{fixture_path("riscv32")}, non_riscv{fixture_path("i386")}
    {
    }

    ~FixtureFiles()
    {
        std::error_code error;
        std::filesystem::remove(riscv, error);
        error.clear();
        std::filesystem::remove(non_riscv, error);
    }

    std::filesystem::path riscv;
    std::filesystem::path non_riscv;
};
} // namespace

int main()
{
    static_assert(!Readelf::is_elf64, "this regression constructs ELF32 fixtures");

    FixtureFiles fixtures;
    check(write_elf32_fixture(fixtures.riscv, EM_RISCV),
          "failed to create the RISC-V ELF32 fixture");
    check(write_elf32_fixture(fixtures.non_riscv, EM_386),
          "failed to create the non-RISC-V ELF32 fixture");

    auto riscv{Readelf::load(fixtures.riscv)};
    check(riscv.has_value(), "a valid RISC-V ELF32 file must load");
    if (riscv)
    {
        check(riscv->loaded(), "the accepted RISC-V ELF must be marked loaded");
        check(riscv->header().e_machine == EM_RISCV,
              "the accepted ELF must preserve its RISC-V machine type");
        check(riscv->section_headers().size() == 5,
              "the accepted ELF must expose its complete section header table");
        check(riscv->functions().size() == 1 && riscv->functions().front().name == "entry",
              "the accepted RISC-V ELF must expose its function symbol");
        check(riscv->find_function_name(0x80000000U) == std::optional<std::string_view>{"entry"},
              "the accepted RISC-V ELF function must be address-queryable");
    }

    const auto non_riscv{Readelf::load(fixtures.non_riscv)};
    check(!non_riscv, "an ELF32 file for a non-RISC-V machine must be rejected");
    if (!non_riscv)
    {
        check(non_riscv.error().contains("RISC-V"),
              "the machine mismatch error must name the required RISC-V architecture");
        check(non_riscv.error().contains("e_machine=3"),
              "the machine mismatch error must report the actual e_machine value");
    }

    if (failures != 0)
        return 1;
    std::println("[PASS] Readelf accepts RISC-V ELF32 and rejects non-RISC-V ELF32");
    return 0;
}
