export module npc.trace.itrace;
import std;
import npc.trace.iringbuf;

export extern iringbuf Iringbuf;
export void RecordAInstruction(std::uint64_t pc, std::uint32_t instruction, int len);
export void PrintIringbuf(std::uint64_t err_pc);
