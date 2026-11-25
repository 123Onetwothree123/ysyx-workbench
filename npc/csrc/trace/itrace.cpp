module npc.trace.itrace;
import npc.Output;
iringbuf Iringbuf;
void PrintIringbuf(std::uint64_t err_pc, Output &out)
{
    Iringbuf.print(err_pc, out);
}
