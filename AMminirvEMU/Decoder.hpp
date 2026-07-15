#include <cstdint>
#ifndef DECODER_HPP
#define DECODER_HPP
#include <stdint.hpp>

class Decoder {
public:
    enum InstrType {
        INSTR_LUI, INSTR_ADD, INSTR_ADDI, INSTR_LW,
        INSTR_LBU, INSTR_JALR, INSTR_SW, INSTR_SB,
        INSTR_EBREAK, INSTR_UNKNOWN
    };
    enum class Format { R, I, S, B, U, J, UNKNOWN };

    Decoder() = default;
    std::uint8_t OpDecode(std::uint32_t inst);
    static Format GetFormat(std::uint32_t inst);
};
#endif