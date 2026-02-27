#include "Decoder.h"
uint8_t Decoder::OpDecode(uint32_t inst)
{
    uint8_t opcode = inst & 0x7F;         // [6:0]
    uint8_t funct3 = (inst >> 12) & 0x07; // [14:12]
    uint8_t funct7 = (inst >> 25) & 0x7F; // [31:25]
    switch (opcode)
    {
        // 0110111: U-type LUI
    case 0x37:
    {
        return INSTR_LUI;
        break;
    }
        // 0010011: I-type ALU (ADDI)
    case 0x13:
    {
        if (funct3 == 0x0)
        {
            return INSTR_ADDI;
        }
        break;
    }
    // 0000011: I-type Load (LW, LBU)
    case 0x03:
    {
        if (funct3 == 0x2)
        {
            return INSTR_LW;
        }
        else if (funct3 == 0x4)
        {
            return INSTR_LBU;
        }
        break;
    }
    // 1100111: I-type Jump (JALR)
    case 0x67:
    {
        if (funct3 == 0x0)
        {
            return INSTR_JALR;
        }
        break;
    }
    // 0110011: R-type (ADD)
    case 0x33:
    {
        if (funct3 == 0x0 && funct7 == 0x00)
        {
            return INSTR_ADD;
        }
        // 如果 funct7 是 0x20，那就是 SUB 指令
        break;
    }
    // 0100011: S-type Store (SW, SB)
    case 0x23:
    {
        if (funct3 == 0x2)
        {
            return INSTR_SW;
        }
        else if (funct3 == 0x0)
        {
            return INSTR_SB;
        }
        break;
    }
    case 0x73:
    {
        if (inst == 0x00100073)
        {
            return INSTR_EBREAK;
        }
        break;
    }
    default:
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