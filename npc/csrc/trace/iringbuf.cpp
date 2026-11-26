module npc.trace.iringbuf;
import npc.Output;
import npc.trace.disasm;
iringbuf::iringbuf() noexcept = default;
iringbuf::~iringbuf() noexcept = default;
bool iringbuf::empty() const
{
    return count == 0;
}
bool iringbuf::full() const
{
    return count == IRINGBUF_SIZE;
}
std::size_t iringbuf::size() const
{
    return count;
}
std::size_t iringbuf::capacity() const
{
    return IRINGBUF_SIZE;
}
void iringbuf::push(std::uint64_t pc, std::uint32_t instruction, int len)
{
    // 指令长度只能 2/4 字节，越界会在打包指令字节时越界读。
    const int bounded_len{std::clamp(len, 0, 4)};
    buffer[head].SetPC(pc);
    buffer[head].SetInstruction(instruction);
    buffer[head].SetLen(bounded_len);
    head = (head + 1) % IRINGBUF_SIZE;
    if (count < IRINGBUF_SIZE)
    {
        count++;
    }
}
void iringbuf::print(std::uint64_t ErrorPC, Output &out) const
{
    if (empty())
    {
        out.Info("iringbuf是空的");
        return;
    }
    const auto start{(head + IRINGBUF_SIZE - count) % IRINGBUF_SIZE};
    out.Info("打印iringbuf");
    for (std::size_t i{0}; i < count; i++)
    {
        const auto index{(start + i) % IRINGBUF_SIZE};
        const RecordInstruction &entry{buffer[index]};
        constexpr std::string_view marker_selected{"-->"};
        constexpr std::string_view marker_normal{"   "};
        std::string_view marker{(entry.GetPC() == ErrorPC) ? marker_selected : marker_normal};
        const auto instruction{entry.GetInstruction()};
        const auto length{static_cast<std::size_t>(
            std::clamp(entry.GetLen(), 0, 4))};
        const auto EncodedBytes{InstructionBytes(instruction, length)};
        std::string BytesText;
        BytesText.reserve(length * 4);
        for (int ByteIndex{static_cast<int>(length) - 1}; ByteIndex >= 0; --ByteIndex)
        {
            std::format_to(std::back_inserter(BytesText), " {:02x}", EncodedBytes[ByteIndex]);
        }
        const auto assembly{DisassembleWord(entry.GetPC(), instruction, length)};
        out.Info("{:s} 0x{:016x}: {:<24s}{:s}",
                 marker,
                 entry.GetPC(),
                 assembly,
                 BytesText);
    }
}
