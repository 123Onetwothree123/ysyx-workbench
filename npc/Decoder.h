#ifndef DECODER_H
#define DECODER_H
#include <stdint.h>
class Decoder
{
public:
    enum InstrType
    {
        INSTR_LUI,
        INSTR_ADD,
        INSTR_ADDI,
        INSTR_LW,
        INSTR_LBU,
        INSTR_JALR,
        INSTR_SW,
        INSTR_SB,
        INSTR_EBREAK,
        INSTR_UNKNOWN
    };
    enum class Format
    {
        R,
        I,
        S,
        B,
        U,
        J,
        UNKNOWN
    };
    Decoder() = default;
    uint8_t OpDecode(uint32_t inst);
    static Format GetFormat(uint32_t inst);
};
#endif