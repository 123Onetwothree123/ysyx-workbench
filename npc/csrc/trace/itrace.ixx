export module npc.trace.itrace;
import std;
import npc.trace.iringbuf;
import npc.trace.disasm;

export extern iringbuf Iringbuf;
export using npc.trace.disasm::init_disasm;
export using npc.trace.disasm::disassemble;
export void RecordAInstruction(std::uint64_t pc, std::uint32_t instruction, int len);
export void PrintIringbuf(std::uint64_t err_pc);
