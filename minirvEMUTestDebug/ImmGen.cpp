#include "ImmGen.h"
#include <cstdint>
int32_t ImmGen::Generate(uint32_t inst)
{
    Decoder::Format fmt = Decoder::GetFormat(inst);
    switch (fmt)
    {
    case Decoder::Format::I:
    {
        return extractI(inst);
    }
    case Decoder::Format::S:
    {
        return extractS(inst);
    }
    case Decoder::Format::U:
    {
        return extractU(inst);
    }
    default:
        return 0;
    }
}
int32_t ImmGen::extractI(uint32_t inst)
{
    int32_t imm = (inst >> 20) & 0xFFF;
    if (imm & 0x800)
    {
        imm |= 0xFFFFF000;
    }
    return imm;
}
int32_t ImmGen::extractS(uint32_t inst)
{
    int32_t imm_11_5 = (inst >> 25) & 0x7F;
    int32_t imm_4_0 = (inst >> 7) & 0x1F;
    int32_t imm = (imm_11_5 << 5) | imm_4_0;
    if (imm & 0x800)
    {
        imm |= 0xFFFFF000;
    }
    return imm;
}
int32_t ImmGen::extractU(uint32_t inst)
{
    return (int32_t)(inst & 0xFFFFF000);
}