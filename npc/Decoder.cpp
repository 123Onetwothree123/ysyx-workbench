#include "Decoder.h"

uint8_t Decoder::OpDecode(uint32_t inst)
{
    uint8_t opcode = inst & 0x7F;
    uint8_t funct3 = (inst >> 12) & 0x07;
    uint8_t funct7 = (inst >> 25) & 0x7F;
    switch (opcode)
    {
    case 0x37:
        return INSTR_LUI;
    case 0x13:
        if (funct3 == 0x0)
            return INSTR_ADDI;
        break;
    case 0x03:
        if (funct3 == 0x2)
            return INSTR_LW;
        else if (funct3 == 0x4)
            return INSTR_LBU;
        break;
    case 0x67:
        if (funct3 == 0x0)
            return INSTR_JALR;
        break;
    case 0x33:
        if (funct3 == 0x0 && funct7 == 0x00)
            return INSTR_ADD;
        break;
    case 0x23:
        if (funct3 == 0x2)
            return INSTR_SW;
        else if (funct3 == 0x0)
            return INSTR_SB;
        break;
    case 0x73:
        if (inst == 0x00100073)
            return INSTR_EBREAK;
        break;
    }
    return INSTR_UNKNOWN;
}
Decoder::Format Decoder::GetFormat(uint32_t inst)
{
    uint8_t opcode = inst & 0x7F;
    switch (opcode)
    {
    case 0x33:
        return Format::R;
    case 0x13:
    case 0x03:
    case 0x67:
    case 0x73:
        return Format::I;
    case 0x23:
        return Format::S;
    case 0x37:
        return Format::U;
    default:
        return Format::UNKNOWN;
    }
}