#ifndef DECODER_H
#define DECODER_H
#include <cstdint>
class Decoder
{
private:
public:
    enum InstrType
    {
        // U-type
        INSTR_LUI,
        // R-type
        INSTR_ADD,
        // I-type
        INSTR_ADDI,
        INSTR_LW,
        INSTR_LBU,
        INSTR_JALR,
        // S-type
        INSTR_SW,
        INSTR_SB,
        INSTR_EBREAK,
        // 错误
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
    ~Decoder() = default;
    uint8_t OpDecode(uint32_t inst);
    static Format GetFormat(uint32_t inst);
};
#endif