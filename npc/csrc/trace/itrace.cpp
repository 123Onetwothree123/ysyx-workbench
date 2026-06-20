module npc.trace.itrace;
iringbuf Iringbuf;
void RecordAInstruction(std::uint64_t pc, std::uint32_t instruction, int len)
{
    Iringbuf.push(pc, instruction, len);
}
void PrintIringbuf(std::uint64_t err_pc)
{
    Iringbuf.print(err_pc);
}
