module minirvemu.ImmGen;

std::int32_t ImmGen::Generate(std::uint32_t inst)
{
    switch (Decoder::GetFormat(inst))
    {
    case Decoder::Format::I: return extractI(inst);
    case Decoder::Format::S: return extractS(inst);
    case Decoder::Format::U: return extractU(inst);
    default: return 0;
    }
}
std::int32_t ImmGen::extractI(std::uint32_t inst)
{
    std::int32_t imm = (inst >> 20) & 0xFFF;
    if (imm & 0x800) imm |= 0xFFFFF000;
    return imm;
}
std::int32_t ImmGen::extractS(std::uint32_t inst)
{
    std::int32_t imm = ((inst >> 25) & 0x7F) << 5 | ((inst >> 7) & 0x1F);
    if (imm & 0x800) imm |= 0xFFFFF000;
    return imm;
}
std::int32_t ImmGen::extractU(std::uint32_t inst)
{
    return static_cast<std::int32_t>(inst & 0xFFFFF000);
}
