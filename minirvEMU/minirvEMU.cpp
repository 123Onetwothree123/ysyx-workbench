#include <cstdint>
#include <print>
#include "minirvEMU.hpp"

std::uint8_t minirvEMU::get_rd(std::uint32_t inst)
{
    return (inst >> 7) & 0x1F;
}
std::uint8_t minirvEMU::get_rs1(std::uint32_t inst)
{
    return (inst >> 15) & 0x1F;
}
std::uint8_t minirvEMU::get_rs2(std::uint32_t inst)
{
    return (inst >> 20) & 0x1F;
}
void minirvEMU::ensure_memory(std::uint32_t word_idx)
{
    if (word_idx >= M.size())
        M.resize(word_idx + 256, 0);
}
minirvEMU::minirvEMU()
{
    R.fill(0);
    M.resize(1024, 0);
}
void minirvEMU::reset()
{
    PC = 0;
    R.fill(0);
    M.clear();
}
std::uint32_t minirvEMU::GetPC() const { return PC; }
void minirvEMU::SetPC(std::uint32_t value) { PC = value; }
std::uint32_t minirvEMU::GetRegister(std::size_t index) const
{
    if (index >= R.size())
        throw std::out_of_range("Register index out of range");
    return R[index];
}
void minirvEMU::SetRegister(std::size_t index, std::uint32_t value)
{
    if (index >= R.size())
        throw std::out_of_range("Register index out of range");
    R[index] = value;
}
std::uint32_t minirvEMU::GetMemory(std::size_t address) const
{
    if (address >= M.size())
        throw std::out_of_range("Memory address out of range");
    return M[address];
}
void minirvEMU::SetMemory(std::size_t address, std::uint32_t value)
{
    if (address >= M.size())
        throw std::out_of_range("Memory address out of range");
    M[address] = value;
}
std::size_t minirvEMU::GetMemorySize() const { return M.size(); }
std::size_t minirvEMU::GetRegisterCount() const { return R.size(); }
void minirvEMU::IncrementPC() { PC++; }

void minirvEMU::PrintState() const
{
    std::println("\n=== Virtual Machine State ===");
    std::println("PC: 0x{:08x}", PC);
    for (std::size_t i{0}; i < R.size(); ++i)
    {
        std::print("R{:2}=0x{:08x}  ", i, R[i]);
        if ((i + 1) % 4 == 0)
            std::println("");
    }
    std::println("\nMemory (First 128 bytes):");
    for (std::size_t i{0}; i < 128; i += 16)
    {
        std::print("  0x{:04x}: ", i);
        for (std::size_t j{0}; j < 16; ++j)
        {
            std::uint8_t b{const_cast<minirvEMU *>(this)->read_byte(i + j)};
            std::print("{:02x} ", b);
        }
        std::println("");
    }
    std::println("=============================\n");
}
void minirvEMU::write_word(std::uint32_t addr, std::uint32_t value)
{
    if (vga.is_vga_addr(addr))
    {
        vga.write_word(addr, value);
        return;
    }
    std::uint32_t idx{addr >> 2};
    ensure_memory(idx);
    M[idx] = value;
}
std::uint32_t minirvEMU::read_word(std::uint32_t addr)
{
    std::uint32_t idx{addr >> 2};
    ensure_memory(idx);
    return M[idx];
}
void minirvEMU::write_byte(std::uint32_t addr, std::uint8_t value)
{
    std::uint32_t idx{addr >> 2};
    std::uint32_t offset{(addr % 4) * 8};
    ensure_memory(idx);
    std::uint32_t mask{~(0xFF << offset)};
    M[idx] = (M[idx] & mask) | (static_cast<std::uint32_t>(value) << offset);
}
std::uint8_t minirvEMU::read_byte(std::uint32_t addr)
{
    std::uint32_t idx{addr >> 2};
    std::uint32_t offset{(addr % 4) * 8};
    ensure_memory(idx);
    return static_cast<std::uint8_t>((M[idx] >> offset) & 0xFF);
}
void minirvEMU::LoadProgram(const std::vector<std::uint32_t> &program)
{
    for (std::size_t i{0}; i < program.size(); ++i)
        write_word(i * 4, program[i]);
}
void minirvEMU::LoadProgram(const std::initializer_list<std::uint32_t> &program)
{
    LoadProgram(std::vector<std::uint32_t>(program));
}
void minirvEMU::step()
{
    if (halted)
        return;

    auto inst{read_word(PC)};
    auto type{decoder.OpDecode(inst)};
    auto imm{immGen.Generate(inst)};
    auto rd{get_rd(inst)};
    auto rs1{get_rs1(inst)};
    auto rs2{get_rs2(inst)};
    std::uint32_t NEXT_PC{PC + 4};

    switch (type)
    {
    case Decoder::InstrType::INSTR_LUI:
        R[rd] = static_cast<std::uint32_t>(imm);
        break;
    case Decoder::InstrType::INSTR_ADD:
        R[rd] = R[rs1] + R[rs2];
        break;
    case Decoder::InstrType::INSTR_ADDI:
        R[rd] = R[rs1] + imm;
        break;
    case Decoder::InstrType::INSTR_LW:
        R[rd] = read_word(R[rs1] + imm);
        break;
    case Decoder::InstrType::INSTR_LBU:
        R[rd] = read_byte(R[rs1] + imm);
        break;
    case Decoder::InstrType::INSTR_SW:
        write_word(R[rs1] + imm, R[rs2]);
        break;
    case Decoder::InstrType::INSTR_SB:
        write_byte(R[rs1] + imm, R[rs2] & 0xFF);
        break;
    case Decoder::InstrType::INSTR_JALR:
    {
        std::uint32_t target{(R[rs1] + imm) & ~1u};
        R[rd] = PC + 4;
        NEXT_PC = target;
        break;
    }
    case Decoder::InstrType::INSTR_EBREAK:
        halted = true;
        if (R[REG_A0] == 0)
            std::println("HIT GOOD TRAP");
        else
            std::println("HIT BAD TRAP");
        return;
    case Decoder::InstrType::INSTR_UNKNOWN:
        std::println("Invalid instruction! Skipping...");
        break;
    }
    R[0] = 0;
    PC = NEXT_PC;
}
bool minirvEMU::IsHalted() const { return halted; }
void minirvEMU::UpdateVGA() { vga.update_screen(); }
