export module npc.elf.ELFPrinter;
import std;
import npc.unicode;
import npc.elf.ELFFile;
import npc.elf.ELFNames;
import npc.elf.ELFQuery;
export namespace npc::elf
{
void print_file_header(std::ostream &os, const Header &header);
void print_section_headers(std::ostream &os,
                           std::span<const SectionHeader> sections,
                           const std::vector<char> &section_name_table);
void print_symbols(std::ostream &os,
                   std::span<const Symbol> symbols,
                   std::span<const SectionHeader> sections,
                   const std::vector<char> &string_table,
                   const std::vector<char> &section_name_table);
}
