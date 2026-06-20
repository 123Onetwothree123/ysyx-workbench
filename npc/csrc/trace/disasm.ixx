export module npc.trace.disasm;
import std;
import npc.capstone;

export void init_disasm();
export void disassemble(char *str, int size, std::uint64_t pc, std::uint8_t *code, int nbyte);
