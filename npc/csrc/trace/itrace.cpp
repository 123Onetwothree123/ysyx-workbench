module npc.trace.itrace;
iringbuf Iringbuf;
void RecordAInstruction(uint64_t pc, uint32_t instruction, int len)
{
    Iringbuf.push(pc, instruction, len);
}
void PrintIringbuf(uint64_t err_pc)
{
    Iringbuf.print(err_pc);
}
