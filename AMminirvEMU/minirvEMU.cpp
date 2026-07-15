#include <cstdint>
#include "minirvEMU.hpp"

// 构造函数定义
minirvEMU::minirvEMU() {
    PC {0};
    halted = false;
    for (int i {0}; i < 16; i++) R[i] {0};
    // 手动清空内存，不依赖 memset
    for (int i {0}; i < 262144; i++) M[i] {0};
}

void minirvEMU::init_vga() { vga.init(); }
void minirvEMU::update_vga() { vga.update_screen(); }
std::uint32_t minirvEMU::GetPC() const { return PC; }
bool minirvEMU::IsHalted() const { return halted; }

void minirvEMU::write_word(std::uint32_t addr, std::uint32_t value) {
    if (vga.is_vga_addr(addr)) {
        vga.write_word(addr, value);
        return;
    }
    std::uint32_t idx = addr >> 2;
    if (idx < 262144) M[idx] = value;
}

std::uint32_t minirvEMU::read_word(std::uint32_t addr) {
    std::uint32_t idx = addr >> 2;
    return (idx < 262144) ? M[idx] : 0;
}

void minirvEMU::write_byte(std::uint32_t addr, std::uint8_t value) {
    if (vga.is_vga_addr(addr)) {
        vga.write_byte(addr, value); // 关键：调用 VGA 的字节写入
        return;
    }
    std::uint32_t idx = addr >> 2;
    std::uint32_t offset = (addr % 4) * 8;
    if (idx < 262144) {
        M[idx] = (M[idx] & ~(0xFF << offset)) | ((std::uint32_t)value << offset);
    }
}

std::uint8_t minirvEMU::read_byte(std::uint32_t addr) {
    std::uint32_t idx = addr >> 2;
    std::uint32_t offset = (addr % 4) * 8;
    return (idx < 262144) ? (std::uint8_t)((M[idx] >> offset) & 0xFF) : 0;
}

void minirvEMU::step() {
    if (halted) return;
    std::uint32_t inst = read_word(PC);
    std::uint8_t type = decoder.OpDecode(inst);
    std::int32_t imm = immGen.Generate(inst);
    std::uint8_t rd = (inst >> 7) & 0x1F;
    std::uint8_t rs1 = (inst >> 15) & 0x1F;
    std::uint8_t rs2 = (inst >> 20) & 0x1F;

    std::uint32_t next_pc = PC + 4;
    std::uint8_t rd_i = rd % 16;
    std::uint8_t rs1_i = rs1 % 16;
    std::uint8_t rs2_i = rs2 % 16;

    switch (type) {
        case Decoder::INSTR_LUI:   R[rd_i] = imm; break;
        case Decoder::INSTR_ADD:   R[rd_i] = R[rs1_i] + R[rs2_i]; break;
        case Decoder::INSTR_ADDI:  R[rd_i] = R[rs1_i] + imm; break;
        case Decoder::INSTR_LW:    R[rd_i] = read_word(R[rs1_i] + imm); break;
        case Decoder::INSTR_LBU:   R[rd_i] = read_byte(R[rs1_i] + imm); break;
        case Decoder::INSTR_SW:    write_word(R[rs1_i] + imm, R[rs2_i]); break;
        case Decoder::INSTR_SB:    write_byte(R[rs1_i] + imm, R[rs2_i] & 0xFF); break;
        case Decoder::INSTR_JALR:  
            next_pc = (R[rs1_i] + imm) & ~1u; 
            R[rd_i] = PC + 4; 
            break;
        case Decoder::INSTR_EBREAK: halted = true; return;
        default: break;
    }
    R[0] {0};
    PC = next_pc;
}