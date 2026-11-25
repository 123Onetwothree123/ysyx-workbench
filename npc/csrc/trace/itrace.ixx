export module npc.trace.itrace;
import std;
import npc.Output;
import npc.trace.iringbuf;

export extern iringbuf Iringbuf;
export void PrintIringbuf(std::uint64_t err_pc, Output &out);
