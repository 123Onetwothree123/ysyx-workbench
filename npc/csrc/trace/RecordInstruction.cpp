module npc.trace.RecordInstruction;
RecordInstruction::RecordInstruction() noexcept = default;
RecordInstruction::~RecordInstruction() = default;
RecordInstruction::RecordInstruction(std::uint64_t pc, std::uint32_t instruction, int len)
{
    SetPC(pc);
    SetInstruction(instruction);
    SetLen(len);
}
std::uint64_t RecordInstruction::GetPC() const
{
    return pc;
}
std::uint32_t RecordInstruction::GetInstruction() const
{
    return instruction;
}
int RecordInstruction::GetLen() const
{
    return len;
}
void RecordInstruction::SetPC(std::uint64_t InputPC)
{
    pc = InputPC;
}
void RecordInstruction::SetInstruction(std::uint32_t InputInstruction)
{
    instruction = InputInstruction;
}
void RecordInstruction::SetLen(int InputLen)
{
    len = InputLen;
}
