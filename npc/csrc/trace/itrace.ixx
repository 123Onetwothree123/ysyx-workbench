export module npc.trace.itrace;
import std;
import npc.trace.iringbuf;

export extern iringbuf Iringbuf;
export void init_disasm();
export void disassemble(char *str, int size, std::uint64_t pc, std::uint8_t *code, int nbyte);
export void RecordAInstruction(std::uint64_t pc, std::uint32_t instruction, int len);
export void PrintIringbuf(std::uint64_t err_pc);
